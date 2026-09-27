'use strict';
/*
 * Проверка клиентской логики без браузера: подменяем DOM минимальным
 * самодельным двойником и прогоняем app.js целиком.
 *
 * Запуск:  node src/test/js/app.test.js
 */

const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const STATIC = path.join(__dirname, '..', '..', 'main', 'resources', 'static');
const fixture = JSON.parse(fs.readFileSync(path.join(__dirname, 'fixture.json'), 'utf8'));
const indexHtml = fs.readFileSync(path.join(STATIC, 'index.html'), 'utf8');

// ------------------------------------------------------------------ двойник DOM

class FakeNode {
    constructor(tag) {
        this.tagName = (tag || 'div').toUpperCase();
        this.children = [];
        this.attributes = {};
        this.listeners = {};
        this.style = {};
        this.dataset = {};
        this.classList = makeClassList(this);
        this._text = '';
        this.value = '';
        this.checked = false;
        this.disabled = false;
        this.options = [];
    }

    set className(value) { this._class = value; }
    get className() { return this._class || ''; }

    set innerHTML(value) { this._html = value; this.children = []; }
    get innerHTML() {
        if (this._html !== undefined) return this._html;
        return this.children.map(c => (c instanceof FakeNode ? c.outerHTML : c.data)).join('');
    }

    set textContent(value) { this._text = String(value); this.children = []; }
    get textContent() {
        if (this._text) return this._text;
        return this.children.map(c => (c instanceof FakeNode ? c.textContent : c.data)).join('');
    }

    get outerHTML() {
        const cls = this.className ? ` class="${this.className}"` : '';
        return `<${this.tagName.toLowerCase()}${cls}>${this.innerHTML}</${this.tagName.toLowerCase()}>`;
    }

    get firstChild() { return this.children[0] || null; }

    append(...nodes) {
        for (const node of nodes) this.children.push(node);
    }

    replaceChildren(...nodes) {
        this.children = nodes;
    }

    addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); }
    removeEventListener() {}
    setAttribute(name, value) { this.attributes[name] = value; }
    removeAttribute(name) { delete this.attributes[name]; }
    getAttribute(name) { return this.attributes[name]; }
    querySelector() { return null; }
    querySelectorAll() { return []; }
    closest() { return null; }
    focus() {}
    click() {}

    matchesSelector(selector) {
        if (selector.startsWith('#')) return this.attributes.id === selector.slice(1);
        return false;
    }
}

function makeClassList(node) {
    const list = () => (node._class || '').split(/\s+/).filter(Boolean);
    return {
        add: (...names) => { node._class = list().concat(names.filter(n => !list().includes(n))).join(' '); },
        remove: (...names) => { node._class = list().filter(n => !names.includes(n)).join(' '); },
        toggle: (name, force) => {
            const has = list().includes(name);
            const want = force === undefined ? !has : force;
            if (want && !has) list().push(name), node._class = list().join(' ');
            if (!want && has) node._class = list().filter(n => n !== name).join(' ');
        },
        contains: name => list().includes(name)
    };
}

/** Разбираем id всех элементов, к которым обращается приложение. */
const requiredIds = [...indexHtml.matchAll(/\bid="([^"]+)"/g)].map(m => m[1]);

const registry = new Map();
for (const id of requiredIds) {
    const node = new FakeNode('div');
    node.attributes.id = id;
    if (id === 'matrixTable' || id === 'expertsTable' || id === 'participantsTable') node.tagName = 'TABLE';
    if (id === 'surveySelect') { node.tagName = 'SELECT'; node.options = []; }
    registry.set(id, node);
}

// document/element — общий объект, на который ссылается и app.js, и тест
const documentStub = {
    createElement: tag => new FakeNode(tag),
    createTextNode: data => ({data}),
    createDocumentFragment: () => new FakeNode('#fragment'),
    createElementNS: (ns, tag) => new FakeNode(tag),
    querySelector: sel => {
        if (sel.startsWith('#')) return registry.get(sel.slice(1)) || new FakeNode('div');
        return new FakeNode('div');
    },
    querySelectorAll: () => [],
    addEventListener: (type, fn) => { documentStub._ready = fn; },
    body: new FakeNode('body'),
    activeElement: null
};

const requests = [];
async function fakeFetch(url, options) {
    const method = (options && options.method) || 'GET';
    requests.push({url, method, body: options && options.body ? JSON.parse(options.body) : null});
    const json = payload => ({ok: true, status: 200, json: async () => payload});

    if (url === '/api/surveys' && method === 'GET') return json([surveyDto()]);
    if (/^\/api\/surveys\/\d+$/.test(url) && method === 'GET') return json(surveyDto());
    if (url.endsWith('/result')) return json(fixture);
    if (/\/choices$/.test(url) && method === 'GET') return json(choicesFromMatrix(fixture.matrix, 'SYMPATHY'));
    if (/\/experts$/.test(url) && method === 'GET') return json([]);
    if (/\/choices$/.test(url) && method === 'POST') return json(surveyDto());
    if (/\/experts$/.test(url) && method === 'POST') return json(surveyDto());
    return json({});
}

function choicesFromMatrix(matrix, kind) {
    const out = [];
    matrix.forEach((row, i) => row.forEach((v, j) => {
        if (v > 0) out.push({from: String(i + 1), to: String(j + 1), kind, weight: 1});
    }));
    return out;
}

function surveyDto() {
    return {
        id: 1,
        name: 'Тестовый опрос',
        description: '',
        choicesLimit: 3,
        collectAntipathy: true,
        ranks: false,
        autoThresholds: true,
        thresholds: {star: 4, preferred: 3, accepted: 1, mostRejected: 2},
        participants: fixture.participants.map(p => ({code: p.code, name: p.name})),
        choicesCount: 31,
        antipathyCount: 2,
        expertsCount: 0,
        respondedCount: 12,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z'
    };
}

const context = {
    Node: FakeNode,
    document: documentStub,
    fetch: fakeFetch,
    window: {print() {}, location: {href: ''}},
    FormData: class { append() {} },
    Blob: class {},
    URL: {createObjectURL: () => 'blob:x', revokeObjectURL() {}},
    setTimeout: (fn) => { fn(); return 0; },   // отложенные сохранения выполняем сразу
    clearTimeout: () => {},
    confirm: () => true,
    prompt: () => 'Психолог',
    alert: () => {},
    console
};
context.globalThis = context;
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(STATIC, 'app.js'), 'utf8'), context, {filename: 'app.js'});
vm.runInContext(fs.readFileSync(path.join(STATIC, 'sociogram.js'), 'utf8'), context, {filename: 'sociogram.js'});

// ------------------------------------------------------------------ проверки

let passed = 0;
const results = [];
async function check(name, fn) {
    try {
        await fn();
        passed++;
        results.push('  ok  ' + name);
    } catch (e) {
        results.push('FAIL  ' + name + '\n      ' + e.message);
        process.exitCode = 1;
    }
}

const app = () => context;
const state = () => vm.runInContext('state', context);
const toggle = (from, to, kind) => vm.runInContext(`toggleChoice(${JSON.stringify(from)}, ${JSON.stringify(to)}, ${JSON.stringify(kind || 'SYMPATHY')})`, context);
const uniqueExperts = () => vm.runInContext('uniqueExperts()', context);
// добавляем эксперта ровно тем же путём, что и пользователь: через prompt
const addExpert = (name) => {
    context.prompt = () => name;
    return vm.runInContext('addExpert()', context);
};

(async () => {
    // запускаем приложение так же, как это делает браузер
    assert.ok(documentStub._ready, 'обработчик DOMContentLoaded не зарегистрирован');
    await documentStub._ready();

    await check('приложение поднимается и грузит опросы без ошибок', () => {
        assert.ok(state().current, 'текущий опрос не выбран');
        assert.ok(state().result, 'результат не загружен');
    });

    await check('состояние загружено: 12 участников и матрица 12x12', () => {
        const s = state();
        assert.strictEqual(vm.runInContext('participants().length', context), 12, 'участники не загрузились');
        assert.strictEqual(s.result.participants.length, 12, 'результат не загрузился');
        assert.strictEqual(s.result.matrix.length, 12);
    });

    await check('список опросов подписан той же подписью, что и заголовок', () => {
        assert.ok(typeof vm.runInContext('surveyLabel', context) === 'function', 'нет функции подписи');
        const select = registry.get('surveySelect');
        assert.strictEqual(select.children.length, 1, 'в списке не один опрос');
        assert.ok(select.children[0].textContent.includes('Тестовый опрос'));
    });

    await check('повторный выбор уже сделанного снимает его', () => {
        const before = state().choices.length;
        toggle('1', '2');
        assert.strictEqual(state().choices.length, before - 1, 'выбор не снялся');
        toggle('1', '2');
        assert.strictEqual(state().choices.length, before, 'выбор не вернулся');
    });

    await check('при сохранении выборов отправляется код респондента', async () => {
        requests.length = 0;
        await vm.runInContext('persistChoices()', context);
        const post = requests.find(r => r.method === 'POST' && /\/choices$/.test(r.url));
        assert.ok(post, 'сохранение выборов не выполнено');
        assert.ok('respondent' in post.body,
            'без respondent сервер не отметит ответ, и «отвечал, но никого не выбрал» потеряется');
        assert.equal(post.body.respondent, '1', 'отправлен не тот респондент, который сейчас выбран');
    });

    await check('себя выбрать нельзя', () => {
        const before = state().choices.length;
        toggle('1', '1');
        assert.strictEqual(state().choices.length, before, 'появился выбор самого себя');
    });

    await check('в ранжированном режиме лимит выборов не применяется', () => {
        const s = state();
        s.current.ranks = true;           // в ранжированном режиме лимит не действует
        const before = s.choices.filter(c => c.from === '3' && c.kind === 'SYMPATHY').length;
        toggle('3', '12');
        const after = state().choices.filter(c => c.from === '3' && c.kind === 'SYMPATHY').length;
        assert.strictEqual(after, before + 1, 'выбор в ранжированном режиме не добавился');
        toggle('3', '12');
        assert.strictEqual(state().choices.filter(c => c.from === '3' && c.kind === 'SYMPATHY').length, before);
        s.current.ranks = false;
    });

    await check('в ранжированном режиме новый выбор получает следующее место', () => {
        const s = state();
        s.current.ranks = true;
        const before = s.choices.filter(c => c.from === '5' && c.kind === 'SYMPATHY').length;
        toggle('5', '12');
        const choice = state().choices.find(c => c.from === '5' && c.to === '12' && c.kind === 'SYMPATHY');
        assert.ok(choice, 'выбор не добавился');
        assert.ok(Number.isInteger(choice.rank) && choice.rank > 0, 'место не проставлено: ' + choice.rank);
        assert.strictEqual(choice.rank, before + 1, 'место не продолжает нумерацию');
        toggle('5', '12');
        s.current.ranks = false;
    });

    await check('в обычном режиме выбор сверх лимита отклоняется', () => {
        const s = state();
        s.current.ranks = false;
        // участник 1 уже выбрал троих при лимите 3
        assert.strictEqual(s.choices.filter(c => c.from === '1' && c.kind === 'SYMPATHY').length, 3);
        const free = ['10', '11', '12'].find(code =>
            !s.choices.some(c => c.from === '1' && c.to === code && c.kind === 'SYMPATHY'));
        toggle('1', free);
        assert.strictEqual(state().choices.filter(c => c.from === '1' && c.kind === 'SYMPATHY').length, 3,
            'выбор сверх лимита прошёл');
    });

    await check('антипатия не попадает в лимит симпатий', () => {
        const s = state();
        const symBefore = s.choices.filter(c => c.from === '2' && c.kind === 'SYMPATHY').length;
        const antiBefore = s.choices.filter(c => c.from === '2' && c.kind === 'ANTIPATHY').length;
        toggle('2', '12', 'ANTIPATHY');
        const s2 = state();
        assert.strictEqual(s2.choices.filter(c => c.from === '2' && c.kind === 'SYMPATHY').length, symBefore);
        assert.strictEqual(s2.choices.filter(c => c.from === '2' && c.kind === 'ANTIPATHY').length, antiBefore + 1);
        toggle('2', '12', 'ANTIPATHY');
    });

    await check('новый эксперт появляется в таблице и не теряет уже введённые оценки', () => {
        const s = state();
        s.ratings = [
            {expert: 'Учитель', code: '1', score: 5},
            {expert: 'Учитель', code: '2', score: 7}
        ];
        addExpert('Психолог');
        assert.ok(uniqueExperts().includes('Психолог'), 'новый эксперт не появился');
        assert.ok(uniqueExperts().includes('Учитель'), 'прежний эксперт пропал');
        // правим одну ячейку нового эксперта
        vm.runInContext("setRating('Психолог', '1', '8')", context);
        const draft = state().expertDraft;
        assert.ok(draft.has('Психолог\u00001'), 'черновик не записан');
        assert.strictEqual(draft.get('Психолог\u00001'), '8');
    });

    await check('сохранение экспертов не затирает оценки других экспертов', async () => {
        vm.runInContext("setRating('Психолог', '2', '9')", context);
        await vm.runInContext('saveExperts()', context);
        const sent = requests.filter(r => r.url.endsWith('/experts') && r.method === 'POST').pop();
        const ratings = sent.body.ratings;
        assert.ok(ratings.length >= 4, 'сохранено меньше четырёх оценок: ' + JSON.stringify(ratings));
        const byKey = new Map(ratings.map(r => [r.expert + '|' + r.code, r.score]));
        assert.strictEqual(byKey.get('Учитель|1'), 5, 'оценка прежнего эксперта потеряна');
        assert.strictEqual(byKey.get('Учитель|2'), 7, 'вторая оценка прежнего эксперта потеряна');
        assert.strictEqual(byKey.get('Психолог|1'), 8, 'новая оценка не сохранена');
        assert.strictEqual(byKey.get('Психолог|2'), 9, 'вторая новая оценка не сохранена');
    });

    await check('эксперт с именем из нескольких слов не ломает ключ', () => {
        const s = state();
        s.ratings = [];
        s.expertDraft = null;
        s.draftExperts = new Set();
        addExpert('Классный руководитель');
        vm.runInContext("setRating('Классный руководитель', '3', '4')", context);
        assert.ok(state().expertDraft.has('Классный руководитель\u00003'),
            'ключ черновика разъехался: ' + Array.from(state().expertDraft.keys()).join('|'));
    });

    await check('пустая ячейка эксперта не превращается в нулевую оценку', async () => {
        const s = state();
        s.ratings = [{expert: 'Учитель', code: '1', score: 5}];
        s.expertDraft = new Map([['Учитель\u00001', '']]);
        s.draftExperts = new Set();
        await vm.runInContext('saveExperts()', context);
        const sent = requests.filter(r => r.url.endsWith('/experts') && r.method === 'POST').pop();
        assert.strictEqual(sent.body.ratings.length, 0,
            'пустая ячейка сохранена как оценка: ' + JSON.stringify(sent.body.ratings));
    });

    await check('индекс отвержения показывается как процент', () => {
        const r = state().result;
        assert.ok('rejectedIndex' in r.indices, 'в индексах нет rejectedIndex');
        assert.ok('negativeStatusIndex' in r.indices, 'в индексах нет negativeStatusIndex');
    });

    await check('несвязный граф показывает прочерк вместо диаметра и число компонент', () => {
        const r = state().result;
        assert.ok(r.graph.connectedComponents > 1, 'в фикстуре должен быть несвязный граф');
        assert.ok(!('diameter' in r.graph), 'диаметр несвязного графа не определён и не приходит числом');
        assert.ok(Array.isArray(r.graph.componentRadii), 'нет радиусов по компонентам');
        assert.equal(r.graph.componentRadii.length, r.graph.connectedComponents);
    });

    await check('категории статуса включают низкопринятых и «нет данных»', () => {
        const counts = state().result.categoriesCount;
        assert.ok('LOW_ACCEPTED' in counts, 'в разбивке нет LOW_ACCEPTED');
        assert.ok('NO_DATA' in counts, 'в разбивке нет NO_DATA');
        const ring = vm.runInContext('Soc.RING_ORDER', context);
        assert.equal(ring.length, 8, 'в кольце социограммы должно быть 8 категорий');
        assert.ok(ring.includes('LOW_ACCEPTED'), 'в кольце нет низкопринятых');
        assert.ok(ring.includes('NO_DATA'), 'в кольце нет категории «нет данных»');
    });

    await check('социограмма строится по загруженному результату', () => {
        const svg = vm.runInContext("Soc.render(state.result, {layout:'concentric',arrows:'all',hide:'',antipathy:true,names:true}).svg", context);
        assert.ok(svg.startsWith('<svg'), 'SVG не построен');
        assert.ok(svg.includes('Анна'), 'в подписях нет участников');
    });

    await check('с одним экспертом показывается «нужно минимум два», а не ноль', () => {
        const verdict = vm.runInContext(
            "kendallVerdict({experts:['Психолог']})", context);
        assert.strictEqual(verdict, 'нужно минимум два эксперта', 'вердикт: ' + verdict);
    });

    await check('Kendall W интерпретируется по порогам согласованности', () => {
        assert.ok(vm.runInContext('kendallVerdict({experts:[1,2],kendallW:0.93})', context).includes('высокая'));
        assert.ok(vm.runInContext('kendallVerdict({experts:[1,2],kendallW:0.6})', context).includes('средняя'));
        assert.ok(vm.runInContext('kendallVerdict({experts:[1,2],kendallW:0.2})', context).includes('низкая'));
    });

    console.log(results.join('\n'));
    console.log('\n' + passed + ' проверок пройдено');
})();
