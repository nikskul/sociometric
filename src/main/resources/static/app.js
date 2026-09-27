'use strict';

/**
 * Клиент социометрии: REST-вызовы, локальное состояние и отрисовка всех вкладок.
 * Без фреймворков — состояние в объекте state, перерисовка нужных кусков DOM.
 */

const state = {
    surveys: [],
    current: null,          // SurveyResponse
    choices: [],            // {from,to,kind,rank,weight}
    ratings: [],            // {expert,code,score}
    result: null,           // ResultResponse
    respondent: 0,          // индекс выбранного участника в форме ввода
    matrixKind: 'SYMPATHY',
    pendingFile: null,
    expertDraft: null,      // Map expertKey(expert, code) -> значение как введено
    draftExperts: new Set() // эксперты, добавленные, но ещё не сохранённые
};

// ---------------------------------------------------------------- утилиты

const $ = sel => document.querySelector(sel);
const $$ = sel => Array.from(document.querySelectorAll(sel));

function el(tag, attrs, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(attrs || {})) {
        if (key === 'class') node.className = value;
        else if (key === 'html') node.innerHTML = value;
        else if (key.startsWith('on')) node.addEventListener(key.slice(2).toLowerCase(), value);
        else if (value !== null && value !== undefined && value !== false) node.setAttribute(key, value);
    }
    for (const child of children.flat()) {
        if (child === null || child === undefined || child === false) continue;
        node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
}

/**
 * Замена содержимого узла без «null» на странице.
 *
 * <p>У replaceChildren другой контракт, чем у append: null он превращает в
 * текстовый узел «null», а не пропускает. Условные куски вида
 * `cond ? el(...) : null` — как раз такие, поэтому фильтруем их сами.
 */
function setChildren(node, ...children) {
    node.replaceChildren(
        ...children.flat().filter(child => child !== null && child !== undefined && child !== false));
}

function status(selector, message, kind) {
    const node = $(selector);
    node.textContent = message || '';
    node.className = 'status' + (kind ? ' ' + kind : '');
}

let toastTimer = null;
function toast(message, isError) {
    const node = $('#toast');
    node.textContent = message;
    node.className = 'toast' + (isError ? ' err' : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => node.classList.add('hidden'), 4000);
}

async function api(url, options) {
    const response = await fetch(url, Object.assign({headers: {'Content-Type': 'application/json'}}, options));
    if (!response.ok) {
        let detail = response.status + ' ' + response.statusText;
        try {
            const body = await response.json();
            if (body && body.message) detail = body.message;
        } catch (ignored) { /* тело не JSON */ }
        throw new Error(detail);
    }
    // 204 — это пустой ответ, но и 200 без тела бывает: так отвечает очистка
    // экспертов. Разбирать такой ответ как JSON нельзя — res.json() упадёт
    // с «Unexpected end of JSON input», поэтому читаем текст и решаем по факту.
    const text = await response.text();
    return text ? JSON.parse(text) : null;
}

const num = (value, digits = 2) =>
    (value === null || value === undefined || Number.isNaN(value)) ? '—' : Number(value).toFixed(digits);
const pct = value => (value === null || value === undefined) ? '—' : (value * 100).toFixed(1) + '%';

/** Разделитель в ключе черновика: имена экспертов могут содержать пробелы. */
const KEY_SEP = '\u0000';
const expertKey = (expert, code) => expert + KEY_SEP + code;
function mutualKey(a, b) { return Math.min(a, b) + '-' + Math.max(a, b); }

function findChoice(from, to, kind) {
    return state.choices.find(c => c.from === from && c.to === to && c.kind === kind) || null;
}

function participants() {
    return state.current ? state.current.participants : [];
}

function byCode(code) {
    return participants().find(p => p.code === code) || null;
}

/** Сколько симпатий уже выбрал участник. */
function givenCount(code, kind = 'SYMPATHY') {
    return state.choices.filter(c => c.from === code && c.kind === kind).length;
}

// ---------------------------------------------------------------- загрузка

async function loadSurveys(preferredId) {
    state.surveys = await api('/api/surveys');
    const select = $('#surveySelect');
    select.replaceChildren(...state.surveys.map(s =>
        el('option', {value: s.id}, surveyLabel(s))));
    if (!state.surveys.length) {
        state.current = null;
        renderAll();
        return;
    }
    const id = preferredId && state.surveys.some(s => s.id === preferredId) ? preferredId : state.surveys[0].id;
    select.value = String(id);
    await selectSurvey(id);
}

async function selectSurvey(id) {
    state.current = await api('/api/surveys/' + id);
    state.choices = await api(`/api/surveys/${id}/choices`);
    state.ratings = await api(`/api/surveys/${id}/experts`);
    state.respondent = 0;
    state.result = null;
    state.expertDraft = null;
    state.draftExperts = new Set();
    await refreshResult();
    renderAll();
}

async function refreshResult() {
    if (!state.current) { state.result = null; return; }
    state.result = await api(`/api/surveys/${state.current.id}/result`);
    $('#resultTitle').textContent = `Результаты: ${state.result.name}`;
}

function respondentCode() {
    const list = participants();
    const me = list[state.respondent];
    return me ? me.code : null;
}

/** Сохранить изменения выборов: удалённое убираем, новое добавляем. */
async function persistChoices() {
    const body = {
        kind: 'SYMPATHY',
        replace: false,
        // код респондента нужен, чтобы сервер отметил ответ: «отвечал, но никого
        // не выбрал» не оставляет ни одной строки выбора и иначе теряется
        respondent: respondentCode(),
        choices: state.choices.map(c => ({kind: c.kind, from: c.from, to: c.to, rank: c.rank ?? null, weight: c.weight || 1}))
    };
    state.current = await api(`/api/surveys/${state.current.id}/choices`, {method: 'POST', body: JSON.stringify(body)});
    await refreshResult();
}

/**
 * Полная перезапись набора выборов — так надёжнее, чем точечные добавления.
 * replaceAll означает «в choices лежит всё состояние опроса, обоих видов сразу»,
 * поэтому сервер сверяет его с базой, а не сносит и вставляет заново.
 */
async function replaceAllChoices() {
    const body = {
        replaceAll: true,
        respondent: respondentCode(),
        choices: state.choices.map(c => ({kind: c.kind, from: c.from, to: c.to, rank: c.rank ?? null, weight: c.weight || 1}))
    };
    state.current = await api(`/api/surveys/${state.current.id}/choices`, {method: 'POST', body: JSON.stringify(body)});
    state.choices = await api(`/api/surveys/${state.current.id}/choices`);
    await refreshResult();
}

// ---------------------------------------------------------------- мутации

function toggleChoice(from, to, kind) {
    if (from === to) { toast('Себя выбрать нельзя', true); return; }
    const existing = findChoice(from, to, kind);
    if (existing) {
        state.choices = state.choices.filter(c => c !== existing);
    } else {
        if (kind === 'SYMPATHY' && !state.current.ranks) {
            const limit = state.current.choicesLimit;
            if (givenCount(from, 'SYMPATHY') >= limit) {
                toast(`Лимит — ${limit} выборов. Снимите один из уже отмеченных`, true);
                return;
            }
        }
        const nextRank = (kind === 'SYMPATHY' && state.current.ranks)
            ? givenCount(from, 'SYMPATHY') + 1 : null;
        state.choices.push({from, to, kind, rank: nextRank, weight: 1});
    }
    saveChoicesDebounced();
}

let saveTimer = null;
function saveChoicesDebounced() {
    renderEntry();
    renderMatrix();
    clearTimeout(saveTimer);
    saveTimer = setTimeout(async () => {
        try {
            await replaceAllChoices();
            status('#entryStatus', `Сохранено в ${new Date().toLocaleTimeString('ru-RU')}`, 'ok');
            renderSurveys();
        } catch (e) {
            status('#entryStatus', 'Ошибка сохранения: ' + e.message, 'err');
        }
    }, 400);
}

async function clearMatrix() {
    const kind = state.matrixKind;
    if (!confirm(`Удалить все ${kind === 'SYMPATHY' ? 'симпатии' : 'антипатии'}?`)) return;
    state.current = await api(`/api/surveys/${state.current.id}/choices?kind=${kind}`, {method: 'DELETE'});
    state.choices = await api(`/api/surveys/${state.current.id}/choices`);
    await refreshResult();
    renderEntry();
    renderMatrix();
    renderSurveys();
    status('#entryStatus', 'Очищено', 'ok');
}

// ---------------------------------------------------------------- отрисовка

function renderAll() {
    renderSettings();
    renderEntry();
    renderMatrix();
    renderResults();
    renderSociogram();
    renderExperts();
    renderSurveys();
    $('#btnDeleteSurvey').disabled = !state.current;
}

function surveyLabel(survey) {
    return `${survey.name || 'Без названия'} · ${survey.participants.length} чел.`
        + ` · выбора: ${survey.choicesCount}`
        + (survey.expertsCount ? ` · эксперты: ${survey.expertsCount}` : '');
}

function renderSurveys() {
    const select = $('#surveySelect');
    if (!state.current) return;
    // держим сводку выбранного опроса в актуальном состоянии после правок
    const index = state.surveys.findIndex(s => s.id === state.current.id);
    if (index >= 0) state.surveys[index] = state.current;
    for (const option of select.options) {
        const survey = state.surveys.find(s => String(s.id) === option.value);
        if (survey) {
            option.textContent = surveyLabel(survey);
        }
    }
}

function renderSettings() {
    const s = state.current;
    const th = (s && s.thresholds) || {star: 3, preferred: 2, accepted: 1, mostRejected: 2};
    $('#sName').value = s ? s.name || '' : '';
    $('#sDescription').value = s ? s.description || '' : '';
    $('#sLimit').value = s ? s.choicesLimit : 3;
    $('#sAntipathy').checked = !!(s && s.collectAntipathy);
    $('#sRanks').checked = !!(s && s.ranks);
    $('#sAuto').checked = !!(s && s.autoThresholds);
    $('#thStar').value = th.star;
    $('#thPreferred').value = th.preferred;
    $('#thAccepted').value = th.accepted;
    $('#thMostRejected').value = th.mostRejected;
    $('#thresholdsBox').classList.toggle('hidden', !($('#sAuto').checked));

    const body = $('#participantsTable tbody');
    const list = s ? s.participants : [];
    body.replaceChildren(...list.map((p, i) => el('tr', {},
        el('td', {class: 'row-num'}, String(i + 1)),
        el('td', {}, el('input', {
            type: 'text', value: p.code, maxlength: 64, 'aria-label': 'Код',
            onchange: e => { list[i].code = e.target.value.trim(); }
        })),
        el('td', {}, el('input', {
            type: 'text', value: p.name || '', maxlength: 255, 'aria-label': 'Имя',
            oninput: e => { list[i].name = e.target.value; }
        })),
        el('td', {}, el('button', {
            class: 'btn btn-small btn-ghost', title: 'Убрать',
            onclick: () => { list.splice(i, 1); renderSettings(); }
        }, '✕')))));
    if (!list.length) {
        body.append(el('tr', {}, el('td', {colspan: 4, class: 'hint'}, 'Участников пока нет. Добавьте их вручную или массово.')));
    }
}

function renderEntry() {
    const list = participants();
    const select = $('#respondentSelect');
    select.replaceChildren(...list.map((p, i) =>
        el('option', {value: String(i)}, `${p.code}. ${p.name || 'без имени'} — выбрал: ${givenCount(p.code)}`)));
    if (list.length) {
        state.respondent = Math.min(Math.max(0, state.respondent), list.length - 1);
        select.value = String(state.respondent);
    }
    const head = $('#respondentHead');
    head.replaceChildren();
    if (!list.length) {
        head.append(el('span', {class: 'hint'}, 'Сначала добавьте участников на вкладке «Опрос».'));
        $('#symChoices').replaceChildren();
        $('#antiChoices').replaceChildren();
        $('#antiBlock').classList.add('hidden');
        return;
    }
    const me = list[state.respondent];
    head.append(el('span', {class: 'name'}, `${me.code}. ${me.name || 'без имени'}`),
        el('span', {class: 'hint'}, `участник №${state.respondent + 1} из ${list.length}`));

    renderChips('#symChoices', me, 'SYMPATHY', false);
    $('#antiBlock').classList.toggle('hidden', !state.current.collectAntipathy);
    renderChips('#antiChoices', me, 'ANTIPATHY', true);

    const sym = givenCount(me.code, 'SYMPATHY');
    const counter = $('#symCounter');
    counter.textContent = state.current.ranks
        ? `выбрано: ${sym}`
        : `${sym} из ${state.current.choicesLimit}`;
    counter.classList.toggle('over', !state.current.ranks && sym > state.current.choicesLimit);
    const anti = $('#antiCounter');
    const antiCount = givenCount(me.code, 'ANTIPATHY');
    anti.textContent = state.current.collectAntipathy
        ? `выбрано: ${antiCount}` : '';
    anti.classList.toggle('hidden', !state.current.collectAntipathy);
}

function renderChips(selector, me, kind, isAnti) {
    const container = $(selector);
    const codes = state.current.ranks && !isAnti
        ? state.choices.filter(c => c.from === me.code && c.kind === kind)
            .sort((a, b) => (a.rank ?? 99) - (b.rank ?? 99)).map(c => c.to)
        : participants().map(p => p.code);
    const fragment = document.createDocumentFragment();
    if (state.current.ranks && !isAnti) {
        fragment.append(el('span', {class: 'hint'},
            'Порядок выбора важен — щёлкните по участнику, чтобы поставить его на следующее место.'));
    }
    codes.forEach(code => {
        const target = byCode(code);
        const choice = findChoice(me.code, code, kind);
        const isMe = code === me.code;
        const rank = choice && choice.rank ? el('span', {class: 'rank'}, `№${choice.rank}`) : null;
        const chip = el('div', {
            class: 'chip' + (choice ? ' on' : '') + (isAnti && choice ? ' anti' : '') + (isMe ? ' me' : ''),
            title: isMe ? 'Себя выбрать нельзя' : 'Щелчок — отметить выбор',
            onclick: () => toggleChoice(me.code, code, kind)
        },
            el('span', {class: 'code'}, code),
            el('span', {}, (target && target.name) || '—'),
            rank);
        if (isAnti && choice) chip.classList.add('anti');
        fragment.append(chip);
    });
    container.replaceChildren(fragment);
}

function renderMatrix() {
    const table = $('#matrixTable');
    const list = participants();
    const kind = state.matrixKind;
    if (!list.length) {
        table.replaceChildren(el('caption', {class: 'hint'}, 'Нет участников'));
        return;
    }
    const isAnti = kind === 'ANTIPATHY';
    const mutual = new Set(state.choices
        .filter(c => c.kind === 'SYMPATHY' && findChoice(c.to, c.from, 'SYMPATHY'))
        .map(c => mutualKey(list.findIndex(p => p.code === c.from), list.findIndex(p => p.code === c.to))));

    const head = el('tr', {}, el('th', {}, 'Кто → кого'),
        ...list.map(p => el('th', {title: p.name || ''}, p.code)));
    const rows = list.map((from, i) => el('tr', {},
        el('th', {title: from.name || ''}, `${from.code}. ${from.name || '—'}`),
        ...list.map((to, j) => {
            if (i === j) return el('td', {class: 'self', title: 'Себя выбрать нельзя'}, '·');
            const choice = findChoice(from.code, to.code, kind);
            const cell = el('td', {
                class: 'cell' + (choice ? ' on' : '') + (isAnti ? ' anti' : '')
                    + (!isAnti && choice && mutual.has(mutualKey(i, j)) ? ' mutual' : ''),
                title: choice ? 'Щелчок — снять выбор' : 'Щелчок — отметить выбор',
                onclick: () => toggleChoice(from.code, to.code, kind)
            });
            if (choice && state.current.ranks && choice.rank) cell.append(el('span', {class: 'rank'}, choice.rank));
            else if (choice) cell.append('✓');
            return cell;
        })));
    table.replaceChildren(el('thead', {}, head), el('tbody', {}, ...rows));
}

// ---------------------------------------------------------------- результаты

function renderResults() {
    const host = $('#resultContent');
    const r = state.result;
    if (!r || !state.current) { host.replaceChildren(); return; }
    const showAnti = $('#showAnti').checked;
    const onlyResponded = $('#onlyResponded').checked;
    const i = r.indices;

    const tiles = el('div', {class: 'tiles'},
        tile('Участников', r.n, `учитывается ${r.n} в матрице`),
        tile('Всего выборов', i.totalSelections, `в среднем ${num(i.meanSelectionsPerPerson)} от участника`),
        tile('Взаимных пар', i.mutualPairsCount, `из ${i.possiblePairsCount} возможных`),
        tile('Групповой индекс GSC', num(i.gsc, 3), r.gscFormula || '2M / n(n-1)'),
        tile('Индекс взаимности', pct(i.reciprocityIndex), `${i.mutualPairsCount} из ${i.mutualPairsCount + i.unidirectionalPairsCount} связей парные`),
        tile('Плотность сети', pct(i.density), `доля связей от всех возможных`),
        tile('Индекс изолированности', pct(i.isolationIndex), 'доля не получающих выборов'),
        tile('Индекс отвержения', pct(i.rejectedIndex), 'доля получивших антипатию'),
        tile('Негативных статусов', pct(i.negativeStatusIndex), 'изолированные, отвергаемые, аутсайдеры'),
        tile('Средний статус', num(r.meanStatus), `σ = ${num(r.sdStatus)}`));

    const graph = r.graph;
    const graphCard = el('div', {class: 'card'},
        el('h2', {}, 'Структура группы'),
        el('div', {class: 'tiles'},
            tile('Диаметр графа', graph.diameter === null || graph.diameter === undefined
                    ? '—' : graph.diameter,
                graph.diameter === null || graph.diameter === undefined
                    ? `граф несвязен (${graph.connectedComponents} компонент)`
                    : 'наибольшее расстояние между участниками'),
            tile('Радиус графа', graph.radius, 'наименьший максимальный путь'),
            tile('Среднее расстояние', num(graph.meanDistance, 3), 'по достижимым парам'),
            tile('Плотность связности',
                pct(graph.connectedPairs / Math.max(1, graph.possiblePairs)),
                `${graph.connectedPairs} из ${graph.possiblePairs} пар достижимы`)),
        r.strongest && r.strongest.length
            ? el('p', {class: 'hint'}, 'Самая тесная микрогруппа (взаимные связи): '
                + r.strongest.map(c => (byCode(c) || {}).name || c).join(', '))
            : null);

    const dist = Object.entries(r.categoriesCount).filter(([, count]) => count > 0);
    const total = dist.reduce((sum, [, count]) => sum + count, 0) || 1;
    const distribution = el('div', {class: 'distribution'},
        ...dist.map(([name, count]) => {
            const color = (Soc.COLORS[name] || '#ccd2dc');
            return el('div', {
                style: `width:${(count / total * 100).toFixed(2)}%;background:${color}`,
                title: `${title(name)}: ${count} чел.`
            }, (count / total > 0.08 ? count : ''));
        }));

    const rows = r.participants.filter(p => !onlyResponded || p.responded);
    const table = el('table', {class: 'status-table'},
        el('thead', {}, el('tr', {},
            el('th', {}, 'Код'), el('th', {}, 'Участник'),
            el('th', {class: 'num'}, 'Получено'), el('th', {class: 'num'}, 'Выбрано'),
            el('th', {class: 'num'}, 'Доля'), el('th', {}, 'Категория'),
            showAnti ? el('th', {}, 'Антипатии') : null,
            el('th', {class: 'num'}, 'Взаимных'), el('th', {class: 'num'}, 'Связность'))),
        el('tbody', {}, ...rows.map(p => el('tr', {},
            el('td', {}, p.code),
            el('td', {}, p.name || '—'),
            el('td', {class: 'num'}, String(p.received)),
            el('td', {class: 'num'}, String(p.given)),
            el('td', {class: 'num'}, pct(p.normalizedReceived)),
            el('td', {}, el('span', {class: 'badge badge-' + p.category}, p.categoryTitle)),
            showAnti ? el('td', {}, el('span', {class: 'badge badge-' + p.antipathyCategory},
                p.rejectedBy ? `${p.antipathyCategoryTitle} (${p.rejectedBy})` : p.antipathyCategoryTitle)) : null,
            el('td', {class: 'num'}, String(p.mutualCount)),
            el('td', {class: 'num'}, String(p.degree))))));

    const matrixCard = renderMatrixView(r);
    const groupsCard = r.groups && r.groups.length ? renderGroups(r.groups) : null;

    setChildren(host,
        el('p', {class: 'hint'},
            `Границы категорий: ${r.autoThresholds ? 'рассчитаны автоматически' : 'заданы вручную'} — `
            + `звезда от ${r.thresholds.star}, предпочитаемый от ${r.thresholds.preferred}, `
            + `принятый от ${r.thresholds.accepted}, низкопринятый — от 1; `
            + `наиболее отвергаемый — ${r.thresholds.mostRejected} антипатий.`
            + (r.degenerateThresholds
                ? ' Все получили одинаковое число выборов, поэтому «среднее ± σ» '
                    + 'не разделяет группу и пороги заданы по полуцелому среднему.'
                : '')),
        el('div', {class: 'card'}, el('h2', {}, 'Распределение статусов'), distribution,
            el('div', {class: 'legend'}, ...dist.map(([name, count]) =>
                el('span', {}, el('i', {class: 'swatch', style: `background:${Soc.COLORS[name] || '#ccd2dc'}`}),
                    `${title(name)} — ${count}`)))),
        el('div', {class: 'card'}, el('h2', {}, 'Структурные индексы'), tiles),
        graphCard,
        el('div', {class: 'card'}, el('h2', {}, 'Социометрические статусы'),
            el('div', {class: 'matrix-wrap'}, table)),
        groupsCard,
        matrixCard);
}

/** Сравнение индексов по подгруппам — появляется, если коды вида «1А», «1Б». */
function renderGroups(groups) {
    const head = el('tr', {},
        el('th', {}, 'Подгруппа'), el('th', {class: 'num'}, 'Участников'),
        el('th', {class: 'num'}, 'GSC'), el('th', {class: 'num'}, 'Взаимность'),
        el('th', {class: 'num'}, 'Плотность'), el('th', {class: 'num'}, 'Взаимных пар'),
        el('th', {class: 'num'}, 'Средний статус'));
    const rows = groups.map(group => {
        const g = group.indices;
        return el('tr', {},
            el('td', {}, group.name || '—'),
            el('td', {class: 'num'}, String(g.n)),
            el('td', {class: 'num'}, num(g.gsc, 3)),
            el('td', {class: 'num'}, pct(g.reciprocityIndex)),
            el('td', {class: 'num'}, pct(g.density)),
            el('td', {class: 'num'}, String(g.mutualPairsCount)),
            el('td', {class: 'num'}, num(g.meanSelectionsPerPerson)));
    });
    return el('div', {class: 'card'},
        el('h2', {}, 'Сравнение подгрупп'),
        el('p', {class: 'hint'},
            'Подгруппа — ведущие цифры кода: «1А» и «1Б» попадут в подгруппу «1», «2А» и «2Б» — в «2». '
            + 'Коды без цифр, например «1» или «12», подгрупп не образуют.'),
        el('div', {class: 'matrix-wrap'},
            el('table', {class: 'status-table'}, el('thead', {}, head), el('tbody', {}, ...rows))));
}

function tile(label, value, note) {
    return el('div', {class: 'tile'},
        el('div', {class: 'label'}, label),
        el('div', {class: 'value'}, String(value)),
        note ? el('div', {class: 'note'}, note) : null);
}

function title(name) {
    return (Soc.TITLES[name] || name);
}

/** Тепловая матрица: интенсивность цвета = вес выбора. */
function renderMatrixView(r) {
    const head = el('tr', {}, el('th', {}, 'Кто → кого'),
        ...r.participants.map(p => el('th', {title: p.name || ''}, p.code)));
    const max = Math.max(1, ...r.matrix.flat());
    const body = el('tbody', {}, ...r.participants.map((from, i) => el('tr', {},
        el('th', {title: from.name || ''}, `${from.code}. ${from.name || '—'}`),
        ...r.participants.map((to, j) => {
            const value = r.matrix[i][j];
            const mutual = value > 0 && r.matrix[j][i] > 0;
            const cell = el('td', {
                class: i === j ? 'self' : (value > 0 ? 'on' : 'off') + (mutual ? ' mutual' : ''),
                style: value > 0 ? `background:rgba(47,111,208,${(0.15 + 0.75 * value / max).toFixed(2)})` : '',
                title: value > 0
                    ? `${from.code} → ${to.code}: вес ${value}${mutual ? ', взаимно' : ''}`
                    : `${from.code} → ${to.code}: нет выбора`
            }, value > 0 ? (value === 1 ? '✓' : String(value)) : '·');
            return cell;
        }))));
    return el('div', {class: 'card'}, el('h2', {}, 'Матрица предпочтений'),
        el('div', {class: 'matrix-wrap'}, el('table', {class: 'matrix heatmap'}, el('thead', {}, head), body)),
        el('p', {class: 'hint'}, 'Насыщенность = вес выбора. Жирные отметки — взаимные пары.'));
}

// ---------------------------------------------------------------- социограмма

function renderSociogram() {
    const wrap = $('#sociogramWrap');
    const legend = $('#socLegend');
    if (!state.result) { wrap.replaceChildren(); legend.replaceChildren(); return; }
    const options = {
        layout: $('#socLayout').value,
        arrows: $('#socArrows').value,
        hide: $('#socHide').value,
        antipathy: $('#socAntipathy').checked,
        names: $('#socNames').checked
    };
    const {svg} = Soc.render(state.result, options);
    wrap.innerHTML = svg;

    const used = [...new Set(state.result.participants
        .filter(p => !(options.hide || '').split(',').includes(p.category))
        .map(p => p.category))];
    setChildren(legend,
        ...used.map(name => el('span', {},
            el('i', {class: 'swatch', style: `background:${Soc.COLORS[name] || '#ccd2dc'}`}), title(name))),
        options.arrows !== 'none' ? el('span', {},
            el('i', {class: 'swatch mutual'}), 'взаимный выбор') : null,
        options.arrows === 'all' ? el('span', {},
            el('i', {class: 'swatch', style: 'background:#a9bddb'}), 'односторонний выбор') : null,
        options.antipathy ? el('span', {},
            el('i', {class: 'swatch', style: 'background:#c0392b'}), 'антипатия (пунктир)') : null);

    // подсветка связей при наведении на участника
    wrap.querySelectorAll('.soc-node').forEach(node => {
        const code = node.dataset.code;
        const show = () => {
            wrap.querySelectorAll('.soc-link').forEach(link => {
                link.classList.toggle('hidden', link.dataset.from !== code && link.dataset.to !== code);
            });
            wrap.querySelectorAll('.soc-node').forEach(other => {
                other.classList.toggle('faded', other !== node);
            });
        };
        const hide = () => {
            wrap.querySelectorAll('.soc-link').forEach(link => link.classList.remove('hidden'));
            wrap.querySelectorAll('.soc-node').forEach(other => other.classList.remove('faded'));
        };
        node.addEventListener('mouseenter', show);
        node.addEventListener('mouseleave', hide);
        node.addEventListener('focus', show);
        node.addEventListener('blur', hide);
    });
}

function download(filename, blob) {
    const url = URL.createObjectURL(blob);
    const link = el('a', {href: url, download: filename});
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function socSvg() {
    const {svg} = Soc.render(state.result, {
        layout: $('#socLayout').value,
        arrows: $('#socArrows').value,
        hide: $('#socHide').value,
        antipathy: $('#socAntipathy').checked,
        names: $('#socNames').checked
    });
    download(`sociogram-${state.current.id}.svg`, new Blob([svg], {type: 'image/svg+xml;charset=utf-8'}));
}

function socPng() {
    const {svg, width, height} = Soc.render(state.result, {
        layout: $('#socLayout').value,
        arrows: $('#socArrows').value,
        hide: $('#socHide').value,
        antipathy: $('#socAntipathy').checked,
        names: $('#socNames').checked
    });
    const blob = new Blob([svg], {type: 'image/svg+xml;charset=utf-8'});
    const url = URL.createObjectURL(blob);
    const image = new Image();
    image.onload = () => {
        const canvas = document.createElement('canvas');
        canvas.width = width * 2;
        canvas.height = height * 2;
        const ctx = canvas.getContext('2d');
        ctx.fillStyle = '#fff';
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.drawImage(image, 0, 0, canvas.width, canvas.height);
        URL.revokeObjectURL(url);
        canvas.toBlob(png => {
            // toBlob возвращает null, если холст не помещается в PNG — это
            // случается на больших группах. Без проверки download() упал бы
            // с «parameter 1 is not of type 'Blob'» и без всякой подсказки.
            if (!png) {
                toast('Не удалось сохранить PNG: картинка слишком большая', true);
                return;
            }
            download(`sociogram-${state.current.id}.png`, png);
        }, 'image/png');
    };
    image.onerror = () => { URL.revokeObjectURL(url); toast('Не удалось построить PNG', true); };
    image.src = url;
}

// ---------------------------------------------------------------- эксперты

function renderExperts() {
    const table = $('#expertsTable');
    const list = participants();
    if (!list.length) {
        table.replaceChildren(el('caption', {class: 'hint'}, 'Нет участников'));
        $('#expertStats').replaceChildren();
        return;
    }
    const names = uniqueExperts();
    if (!names.length) {
        table.replaceChildren(el('caption', {class: 'hint'},
            'Экспертов пока нет. Нажмите «+ Эксперт», чтобы добавить оценки.'));
    } else {
        const head = el('tr', {}, el('th', {}, 'Эксперт'),
            ...list.map(p => el('th', {title: p.name || ''}, p.code)),
            el('th', {class: 'num'}, 'Средняя'));
        const rows = names.map(expert => {
            const inputs = list.map(p => {
                const value = ratingValue(expert, p.code);
                return el('td', {}, el('input', {
                    type: 'number', min: 0, max: 100, value: value ?? '', placeholder: '—',
                    style: 'width:64px;text-align:center;border:0;background:transparent',
                    'aria-label': `${expert}, ${p.code}`,
                    oninput: e => setRating(expert, p.code, e.target.value)
                }));
            });
            const values = list.map(p => ratingValue(expert, p.code)).filter(v => v !== null);
            const mean = values.length ? values.reduce((a, b) => a + b, 0) / values.length : null;
            return el('tr', {}, el('th', {}, expert), ...inputs,
                el('td', {class: 'num'}, mean === null ? '—' : num(mean, 1)));
        });
        table.replaceChildren(el('thead', {}, head), el('tbody', {}, ...rows));
    }

    const stats = state.result && state.result.experts;
    const host = $('#expertStats');
    if (!stats || !stats.experts.length) {
        host.replaceChildren(el('p', {class: 'hint'},
            'Как только оценки будут сохранены, здесь появятся коэффициент согласованности Kendall W, '
            + 'корреляция с реальным социометрическим статусом и сопоставление рангов.'));
        return;
    }
    const kendall = stats.kendallW;
    const verdict = kendallVerdict(stats);
    const tiles = el('div', {class: 'tiles'},
        tile('Kendall W', num(kendall, 3), verdict + ' (экспертов: ' + stats.experts.length + ')'),
        tile('Пирсон r', num(stats.pearsonWithStatus, 3), 'оценка против полученных выборов'),
        tile('Спирмен ρ', num(stats.spearmanWithStatus, 3), 'ранговая корреляция'));

    const header = el('tr', {},
        el('th', {}, 'Место'), el('th', {}, 'Код'), el('th', {}, 'Участник'),
        el('th', {class: 'num'}, 'Ранг экспертов'),
        el('th', {class: 'num'}, 'Социометрический ранг'),
        el('th', {class: 'num'}, 'Расхождение'));

    const rows = stats.ranking.map(row => el('tr', {},
        el('td', {}, String(row.position)),
        el('td', {}, row.code),
        el('td', {}, row.name || '—'),
        el('td', {class: 'num'}, String(row.expertRank)),
        el('td', {class: 'num'}, String(row.sociometricRank)),
        el('td', {
            class: 'num',
            style: Math.abs(row.difference) >= 3 ? 'color:#c0392b;font-weight:600' : ''
        }, (row.difference > 0 ? '+' : '') + row.difference)));

    const ranking = el('table', {class: 'status-table'},
        el('thead', {}, header), el('tbody', {}, ...rows));

    host.replaceChildren(tiles, el('div', {class: 'matrix-wrap'}, ranking));
}

/** Как читать Kendall W: с одним экспертом коэффициент не вычислим вовсе. */
function kendallVerdict(stats) {
    const w = stats.kendallW;
    if (w === null || w === undefined) {
        return 'нужно минимум два эксперта';
    }
    if (w >= 0.8) return 'высокая согласованность';
    if (w >= 0.5) return 'средняя согласованность';
    return 'низкая согласованность';
}

function uniqueExperts() {
    const seen = new Set(state.ratings.map(r => r.expert));
    state.draftExperts.forEach(name => seen.add(name));
    return Array.from(seen).sort((a, b) => a.localeCompare(b, 'ru'));
}

function ratingValue(expert, code) {
    if (state.expertDraft) {
        const raw = state.expertDraft.get(expert + '\u0000' + code);
        return raw === undefined || raw === '' ? null : Number(raw);
    }
    const found = state.ratings.find(r => r.expert === expert && r.code === code);
    return found ? found.score : null;
}

function setRating(expert, code, raw) {
    if (!state.expertDraft) state.expertDraft = new Map();
    state.expertDraft.set(expert + '\u0000' + code, raw);
    status('#expertsStatus', 'Есть несохранённые изменения');
    // обновляем только средние в правом столбце
    renderExperts();
}

async function saveExperts() {
    // Черновик накладываем поверх сохранённых оценок: иначе правка одной ячейки
    // стёрла бы все остальные оценки того же эксперта.
    const merged = new Map(state.ratings.map(r => [expertKey(r.expert, r.code), r.score]));
    if (state.expertDraft) {
        for (const [key, raw] of state.expertDraft) {
            if (raw === '' || raw === null || Number.isNaN(Number(raw))) {
                merged.delete(key);
            } else {
                merged.set(key, Math.round(Number(raw)));
            }
        }
    }
    const ratings = Array.from(merged, ([key, score]) => {
        const index = key.indexOf(KEY_SEP);
        return {expert: key.slice(0, index), code: key.slice(index + 1), score};
    });
    state.current = await api(`/api/surveys/${state.current.id}/experts`, {
        method: 'POST',
        body: JSON.stringify({ratings, replace: true})
    });
    state.ratings = await api(`/api/surveys/${state.current.id}/experts`);
    state.expertDraft = null;
    state.draftExperts = new Set();
    await refreshResult();
    renderExperts();
    renderSurveys();
    status('#expertsStatus', 'Оценки сохранены', 'ok');
}

/** Добавляет эксперта по имени; возвращает false, если имя не подходит. */
function addExpertByName(name) {
    const list = participants();
    if (!list.length) { toast('Сначала добавьте участников', true); return false; }
    if (!name || !name.trim()) return false;
    const expert = name.trim();
    if (uniqueExperts().includes(expert)) { toast('Такой эксперт уже есть', true); return false; }
    if (!state.expertDraft) state.expertDraft = new Map();
    for (const p of list) state.expertDraft.set(expertKey(expert, p.code), '');
    state.draftExperts.add(expert);
    renderExperts();
    status('#expertsStatus', 'Оценки эксперта «' + expert + '» добавлены, введите баллы и сохраните');
    return true;
}

async function addExpert() {
    return addExpertByName(prompt('Имя эксперта:'));
}

async function clearExperts() {
    if (!confirm('Удалить все экспертные оценки?')) return;
    state.current = await api(`/api/surveys/${state.current.id}/experts`, {method: 'DELETE'});
    state.ratings = [];
    state.expertDraft = null;
    state.draftExperts = new Set();
    await refreshResult();
    renderExperts();
    renderSurveys();
    status('#expertsStatus', 'Оценки очищены', 'ok');
}

// ---------------------------------------------------------------- сохранение опроса

async function saveSurvey() {
    if (!state.current) return;
    const list = participants();
    const codes = new Set();
    for (const p of list) {
        if (!p.code) { toast('У всех участников должен быть код', true); return; }
        if (codes.has(p.code)) { toast('Код «' + p.code + '» повторяется', true); return; }
        codes.add(p.code);
    }
    const payload = {
        name: $('#sName').value.trim() || 'Без названия',
        description: $('#sDescription').value.trim(),
        choicesLimit: Number($('#sLimit').value) || 3,
        collectAntipathy: $('#sAntipathy').checked,
        ranks: $('#sRanks').checked,
        autoThresholds: $('#sAuto').checked,
        thresholds: {
            star: Number($('#thStar').value),
            preferred: Number($('#thPreferred').value),
            accepted: Number($('#thAccepted').value),
            mostRejected: Number($('#thMostRejected').value)
        },
        participants: list.map(p => ({code: String(p.code), name: p.name || ''}))
    };
    try {
        state.current = await api('/api/surveys/' + state.current.id, {method: 'PUT', body: JSON.stringify(payload)});
        // коды могли измениться — перечитываем выборы, чтобы почистить осиротевшие
        const dropped = state.choices.filter(c => !codes.has(c.from) || !codes.has(c.to));
        if (dropped.length) {
            state.choices = state.choices.filter(c => !dropped.includes(c));
            await replaceAllChoices();
        }
        await loadSurveys(state.current.id);
        status('#settingsStatus', 'Сохранено в ' + new Date().toLocaleTimeString('ru-RU'), 'ok');
    } catch (e) {
        status('#settingsStatus', 'Ошибка: ' + e.message, 'err');
    }
}

async function newSurvey() {
    const created = await api('/api/surveys', {
        method: 'POST',
        body: JSON.stringify({
            name: 'Новый опрос', description: '', choicesLimit: 3,
            collectAntipathy: true, ranks: false, autoThresholds: true,
            thresholds: {star: 3, preferred: 2, accepted: 1, mostRejected: 2},
            participants: [{code: '1', name: 'Участник 1'}]
        })
    });
    await loadSurveys(created.id);
    status('#settingsStatus', 'Создан новый опрос', 'ok');
}

async function deleteSurvey() {
    if (!state.current) return;
    if (!confirm(`Удалить опрос «${state.current.name}» со всеми данными?`)) return;
    await api('/api/surveys/' + state.current.id, {method: 'DELETE'});
    state.current = null;
    await loadSurveys();
}

// ---------------------------------------------------------------- импорт

function pickFile(file) {
    if (!file) return;
    state.pendingFile = file;
    $('#btnImport').disabled = false;
    $('#dropzone p').firstChild.textContent = `Файл: ${file.name} (${(file.size / 1024).toFixed(1)} КБ) `;
    status('#importStatus', 'Готово к загрузке');
}

async function importFile() {
    if (!state.pendingFile) return;
    const form = new FormData();
    form.append('file', state.pendingFile);
    const replace = $('#importReplace').checked;
    try {
        const report = await api(`/api/surveys/${state.current.id}/import?replace=${replace}`, {method: 'POST', body: form});
        state.choices = await api(`/api/surveys/${state.current.id}/choices`);
        state.ratings = await api(`/api/surveys/${state.current.id}/experts`);
        await refreshResult();
        renderEntry(); renderMatrix(); renderResults(); renderExperts(); renderSurveys();
        status('#importStatus',
            `Загружено ${report.imported}, пропущено строк: ${report.skippedRows} · участников в опросе: ${report.participantsCount}`,
            'ok');
        setChildren($('#importReport'), report.warnings && report.warnings.length
            ? el('div', {class: 'report'}, el('strong', {}, 'Замечания:'),
                el('ul', {}, ...report.warnings.slice(0, 50).map(w => el('li', {}, w))))
            : null);
    } catch (e) {
        status('#importStatus', 'Ошибка: ' + e.message, 'err');
    }
}

function downloadTemplate() {
    window.location.href = `/api/surveys/${state.current.id}/template.csv`;
}

function exportResult(format) {
    if (!state.current) return;
    window.location.href = `/api/surveys/${state.current.id}/export?format=${format}`;
}

// ---------------------------------------------------------------- события

/**
 * Шапка для печати: верхняя панель на бумаге скрыта, а лист без названия
 * опроса и даты нельзя ни опознать, ни отнести к нужному классу.
 */
function renderPrintHeader(tab) {
    const title = $('#printTitle');
    const meta = $('#printMeta');
    if (!title || !meta) return;
    const current = state.current;
    title.textContent = current ? current.name : 'Социометрия';
    const parts = [tab ? tab.textContent.trim() : ''];
    if (current) {
        parts.push(current.participants.length + ' участников');
        parts.push('выборов: ' + current.choicesCount
            + (current.antipathyCount ? ', антипатий: ' + current.antipathyCount : ''));
    }
    parts.push(new Date().toLocaleString('ru-RU'));
    meta.textContent = parts.filter(Boolean).join(' · ');
}

function bindEvents() {
    $('#tabs').addEventListener('click', e => {
        const tab = e.target.closest('.tab');
        if (!tab) return;
        $$('.tab').forEach(t => t.classList.toggle('active', t === tab));
        $$('.panel').forEach(p => p.classList.toggle('hidden', p.id !== 'panel-' + tab.dataset.tab));
        renderPrintHeader(tab);
    });

    $('#surveySelect').addEventListener('change', e => selectSurvey(Number(e.target.value)));
    $('#btnNewSurvey').addEventListener('click', newSurvey);
    $('#btnDeleteSurvey').addEventListener('click', deleteSurvey);
    $('#btnSaveSurvey').addEventListener('click', saveSurvey);
    $('#btnAddParticipant').addEventListener('click', () => {
        if (!state.current) return;
        const list = participants();
        let n = list.length + 1;
        while (list.some(p => p.code === String(n))) n++;
        list.push({code: String(n), name: 'Участник ' + n});
        renderSettings();
    });

    $('#sAuto').addEventListener('change', () =>
        $('#thresholdsBox').classList.toggle('hidden', !$('#sAuto').checked));

    $('#btnBulkParticipants').addEventListener('click', () => {
        $('#bulkText').value = participants().map(p => p.code + ':' + (p.name || '')).join('\n');
        $('#bulkDialog').showModal();
    });
    $('#btnBulkApply').addEventListener('click', () => {
        const lines = $('#bulkText').value.split('\n').map(l => l.trim()).filter(Boolean);
        const list = participants();
        const result = [];
        let auto = 1;
        for (const line of lines) {
            const idx = line.indexOf(':');
            const code = (idx > 0 ? line.slice(0, idx) : '').trim();
            const name = idx > 0 ? line.slice(idx + 1).trim() : line;
            let finalCode = code;
            if (!finalCode) { while (result.some(p => p.code === String(auto))) auto++; finalCode = String(auto); }
            result.push({code: finalCode, name});
        }
        if (result.length) {
            result.forEach((p, i) => { list[i] = p; });
            list.length = result.length;
            renderSettings();
        }
        $('#bulkDialog').close();
    });

    $('#respondentSelect').addEventListener('change', e => { state.respondent = Number(e.target.value); renderEntry(); });
    $('#btnPrevResp').addEventListener('click', () => {
        state.respondent = (state.respondent - 1 + participants().length) % Math.max(1, participants().length);
        renderEntry();
    });
    $('#btnNextResp').addEventListener('click', () => {
        state.respondent = (state.respondent + 1) % Math.max(1, participants().length);
        renderEntry();
    });
    $('#matrixKind').addEventListener('change', e => { state.matrixKind = e.target.value; renderMatrix(); });
    $('#btnClearMatrix').addEventListener('click', clearMatrix);
    $('#btnRecalc').addEventListener('click', async () => { await refreshResult(); renderResults(); toast('Пересчитано'); });
    $('#btnPrint').addEventListener('click', () => {
        renderPrintHeader($('.tab.active'));
        window.print();
    });
    $('#btnExportCsv').addEventListener('click', () => exportResult('csv'));
    $('#btnExportXlsx').addEventListener('click', () => exportResult('xlsx'));
    $('#showAnti').addEventListener('change', renderResults);
    $('#onlyResponded').addEventListener('change', renderResults);

    ['socLayout', 'socArrows', 'socHide'].forEach(id => $('#' + id).addEventListener('change', renderSociogram));
    ['socAntipathy', 'socNames'].forEach(id => $('#' + id).addEventListener('change', renderSociogram));
    $('#btnSocSvg').addEventListener('click', socSvg);
    $('#btnSocPng').addEventListener('click', socPng);

    $('#btnAddExpert').addEventListener('click', addExpert);
    $('#btnSaveExperts').addEventListener('click', () => saveExperts().catch(e => status('#expertsStatus', e.message, 'err')));
    $('#btnClearExperts').addEventListener('click', () => clearExperts().catch(e => status('#expertsStatus', e.message, 'err')));

    $('#btnPickFile').addEventListener('click', () => $('#fileInput').click());
    $('#fileInput').addEventListener('change', e => pickFile(e.target.files[0]));
    const dz = $('#dropzone');
    ['dragenter', 'dragover'].forEach(evt => dz.addEventListener(evt, e => {
        e.preventDefault(); dz.classList.add('over');
    }));
    ['dragleave', 'drop'].forEach(evt => dz.addEventListener(evt, e => {
        e.preventDefault(); dz.classList.remove('over');
    }));
    dz.addEventListener('drop', e => pickFile(e.dataTransfer.files[0]));
    $('#btnImport').addEventListener('click', importFile);
    $('#btnTemplate').addEventListener('click', downloadTemplate);
}

// ---------------------------------------------------------------- старт

async function start() {
    bindEvents();
    try {
        await loadSurveys();
    } catch (e) {
        toast('Не удалось загрузить опросы: ' + e.message, true);
    }
}

document.addEventListener('DOMContentLoaded', start);
