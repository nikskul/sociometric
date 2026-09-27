'use strict';
/*
 * Проверка отрисовки социограммы без браузера: sociogram.js — чистые функции,
 * которые собирают строку SVG, поэтому их можно прогнать в обычном node.
 *
 * Запуск:  node src/test/js/sociogram.test.js
 */

const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const here = __dirname;
const source = fs.readFileSync(path.join(here, '..', '..', 'main', 'resources', 'static', 'sociogram.js'), 'utf8');
const context = {module: {exports: {}}, console};
vm.createContext(context);
vm.runInContext(source + '\n;module.exports = Soc;', context);
const Soc = context.module.exports;

const result = JSON.parse(fs.readFileSync(path.join(here, 'fixture.json'), 'utf8'));

let passed = 0;
function check(name, fn) {
    try {
        fn();
        passed++;
        console.log('  ok  ' + name);
    } catch (e) {
        console.log('FAIL  ' + name + '\n      ' + e.message);
        process.exitCode = 1;
    }
}

function options(extra) {
    return Object.assign({layout: 'concentric', arrows: 'all', hide: '', antipathy: false, names: true}, extra);
}

const countOf = (svg, re) => (svg.match(re) || []).length;
const NODES = /class="soc-node"/g;
const LINKS = /class="soc-link /g;

/** Кружки участников с их радиусами. */
function nodes(svg) {
    return [...svg.matchAll(/<circle cx="([\d.-]+)" cy="([\d.-]+)" r="([\d.]+)" fill="#/g)]
        .map(m => ({x: +m[1], y: +m[2], r: +m[3]}));
}

function assertNoOverlap(points, label) {
    for (let i = 0; i < points.length; i++) {
        for (let j = i + 1; j < points.length; j++) {
            const d = Math.hypot(points[i].x - points[j].x, points[i].y - points[j].y);
            assert.ok(d > points[i].r + points[j].r,
                label + ': узлы ' + i + ' и ' + j + ' накладываются (d=' + d.toFixed(1) + ')');
        }
    }
}

function assertInside(points, width, height, label) {
    for (const p of points) {
        assert.ok(p.x - p.r >= 0 && p.x + p.r <= width && p.y - p.r >= 0 && p.y + p.r <= height,
            label + ': узел (' + p.x + ',' + p.y + ') вышел за пределы ' + width + 'x' + height);
    }
}

/** Группа заданного размера со случайными, но воспроизводимыми выборами. */
function synthetic(n, density) {
    let seed = 12345;
    const rnd = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
    const matrix = Array.from({length: n}, () => new Array(n).fill(0));
    for (let i = 0; i < n; i++) {
        for (let j = 0; j < n; j++) {
            if (i !== j && rnd() < density) matrix[i][j] = 1;
        }
    }
    const incoming = matrix.map((row, i) => row.reduce((s, v, j) => s + (matrix[j][i] > 0 ? 1 : 0), 0));
    return {
        name: 'Синтетическая группа',
        n,
        participants: Array.from({length: n}, (_, i) => ({
            index: i,
            code: String(i + 1),
            name: 'Участник ' + (i + 1),
            received: incoming[i],
            given: matrix[i].filter(v => v > 0).length,
            rejectedBy: 0,
            category: incoming[i] >= 3 ? 'STAR' : incoming[i] >= 2 ? 'PREFERRED'
                : incoming[i] >= 1 ? 'ACCEPTED' : 'ISOLATED',
            categoryTitle: '—'
        })),
        matrix,
        antiMatrix: Array.from({length: n}, () => new Array(n).fill(0)),
        mutualPairs: [],
        unidirectionalPairs: []
    };
}

check('все участники попали на схему ровно один раз', () => {
    const {svg} = Soc.render(result, options());
    assert.strictEqual(countOf(svg, NODES), result.participants.length);
    const codes = [...svg.matchAll(/data-code="([^"]+)"/g)].map(m => m[1]);
    assert.strictEqual(new Set(codes).size, codes.length, 'коды участников повторяются');
    result.participants.forEach(p => assert.ok(codes.includes(p.code), 'нет участника ' + p.code));
});

check('каждая стрелка ссылается на существующие узлы', () => {
    const {svg} = Soc.render(result, options());
    const codes = new Set([...svg.matchAll(/data-code="([^"]+)"/g)].map(m => m[1]));
    const links = [...svg.matchAll(/data-from="([^"]+)" data-to="([^"]+)"/g)];
    assert.ok(links.length > 0, 'стрелок не построено вовсе');
    for (const [, from, to] of links) {
        assert.ok(codes.has(from), 'стрелка из неизвестного узла: ' + from);
        assert.ok(codes.has(to), 'стрелка в неизвестный узел: ' + to);
        assert.notStrictEqual(from, to, 'стрелка в самого себя');
    }
});

check('число стрелок равно числу связей в матрице (каждая пара — одна линия)', () => {
    const {svg} = Soc.render(result, options());
    const chosen = result.matrix.reduce((sum, row) => sum + row.filter(v => v > 0).length, 0);
    const pairs = result.mutualPairs.length + result.unidirectionalPairs.length;
    // взаимная пара даёт два направления, односторонняя — одно
    const expected = 2 * result.mutualPairs.length + result.unidirectionalPairs.length;
    assert.strictEqual(chosen, expected, 'матрица несогласована с парами — тест бессмыслен');
    assert.strictEqual(countOf(svg, LINKS), pairs, 'стрелок больше, чем пар');
});

check('взаимная пара — одна линия с двумя наконечниками', () => {
    const {svg} = Soc.render(result, options());
    const mutual = (svg.match(/class="soc-link mutual"/g) || []).length;
    assert.strictEqual(mutual, result.mutualPairs.length);
    assert.strictEqual(countOf(svg, /marker-start=/g), result.mutualPairs.length,
        'у взаимных пар должен быть наконечник с обоих концов');
});

check('«только взаимные» оставляет ровно стрелки взаимных пар', () => {
    const {svg} = Soc.render(result, options({arrows: 'mutual'}));
    assert.strictEqual(countOf(svg, LINKS), result.mutualPairs.length);
    assert.ok(!/soc-link single/.test(svg), 'остались односторонние стрелки');
});

check('«без стрелок» не рисует ни одной связи', () => {
    const {svg} = Soc.render(result, options({arrows: 'none'}));
    assert.strictEqual(countOf(svg, LINKS), 0);
});

check('стрелки антипатий совпадают с числом антипатий', () => {
    const {svg} = Soc.render(result, options({antipathy: true}));
    const anti = result.antiMatrix.reduce((sum, row) => sum + row.filter(v => v > 0).length, 0);
    const drawn = countOf(svg, /class="soc-link anti"/g);
    assert.strictEqual(drawn, anti);
});

check('взаимная пара помечена именно как взаимная', () => {
    const {svg} = Soc.render(result, options());
    const {a, b} = result.mutualPairs[0];
    const from = result.participants[a].code;
    const to = result.participants[b].code;
    const mutual = new RegExp(`class="soc-link mutual"[^>]*data-from="${from}" data-to="${to}"`);
    const reversed = new RegExp(`class="soc-link mutual"[^>]*data-from="${to}" data-to="${from}"`);
    assert.ok(mutual.test(svg) || reversed.test(svg), 'взаимная пара не отмечена');
});

check('скрытие категорий убирает участников и их связи', () => {
    const hidden = ['ISOLATED', 'REJECTED', 'OUTSIDER'];
    const {svg} = Soc.render(result, options({hide: hidden.join(',')}));
    const expected = result.participants.filter(p => !hidden.includes(p.category)).length;
    assert.strictEqual(countOf(svg, NODES), expected);
    const codes = new Set([...svg.matchAll(/data-code="([^"]+)"/g)].map(m => m[1]));
    for (const [, from, to] of svg.matchAll(/data-from="([^"]+)" data-to="([^"]+)"/g)) {
        assert.ok(codes.has(from) && codes.has(to), 'осталась связь со скрытым участником');
    }
});

check('скрытие в середине матрицы не сдвигает связи на чужих участников', () => {
    // Матрицы выровнены по полному списку, people — отфильтрованный. Если брать
    // участника по позиции в отфильтрованном списке, связи уезжают на соседей.
    const hidden = ['PREFERRED'];
    const {svg} = Soc.render(result, options({hide: hidden.join(',')}));

    const shown = result.participants.filter(p => !hidden.includes(p.category));
    const shownCodes = new Set(shown.map(p => p.code));
    const expected = new Set();
    for (let i = 0; i < result.participants.length; i++) {
        for (let j = i + 1; j < result.participants.length; j++) {
            if (result.matrix[i][j] > 0 || result.matrix[j][i] > 0) {
                expected.add(result.participants[i].code + '>' + result.participants[j].code);
            }
        }
    }

    const links = [...svg.matchAll(/data-from="([^"]+)" data-to="([^"]+)"/g)]
        .map(m => m[1] + '>' + m[2]);

    assert.ok(links.length > 0, 'после скрытия не осталось ни одной связи');
    for (const link of links) {
        const [from, to] = link.split('>');
        assert.ok(shownCodes.has(from) && shownCodes.has(to), 'связь со скрытым: ' + link);
        assert.ok(expected.has(link), 'связи нет в исходной матрице: ' + link);
    }
    // связи, не задевающие скрытых, должны сохраниться все
    const untouched = [...expected].filter(link => link.split('>').every(c => shownCodes.has(c)));
    for (const link of untouched) {
        assert.ok(links.includes(link), 'потеряна связь ' + link);
    }
});

check('скрытие антипатий не сдвигает стрелки', () => {
    const withAnti = result.antiMatrix.some((row, i) => row.some((v, j) => v > 0 && i !== j));
    if (!withAnti) return;
    const {svg} = Soc.render(result, options({hide: 'STAR', antipathy: true}));
    const shownCodes = new Set(result.participants.filter(p => p.category !== 'STAR').map(p => p.code));
    for (const m of svg.matchAll(/class="soc-link anti"[^>]*data-from="([^"]+)" data-to="([^"]+)"/g)) {
        assert.ok(shownCodes.has(m[1]) && shownCodes.has(m[2]),
            'антипатия привязана к скрытому участнику: ' + m[1] + '>' + m[2]);
    }
});

check('все три раскладки дают одинаковое число узлов', () => {
    for (const layout of ['concentric', 'star', 'circle']) {
        const {svg} = Soc.render(result, options({layout}));
        assert.strictEqual(countOf(svg, NODES), result.participants.length, layout);
    }
});

check('узлы раскладки не наезжают друг на друга', () => {
    for (const layout of ['concentric', 'star', 'circle']) {
        const {svg} = Soc.render(result, options({layout}));
        const points = [...svg.matchAll(/<circle cx="([\d.-]+)" cy="([\d.-]+)" r="([\d.]+)" fill="#/g)]
            .map(m => ({x: +m[1], y: +m[2], r: +m[3]}));
        assert.ok(points.length > 1, layout + ': слишком мало узлов');
        for (let i = 0; i < points.length; i++) {
            for (let j = i + 1; j < points.length; j++) {
                const d = Math.hypot(points[i].x - points[j].x, points[i].y - points[j].y);
                assert.ok(d > points[i].r + points[j].r,
                    layout + ': узлы ' + i + ' и ' + j + ' пересекаются');
            }
        }
    }
});

check('узлы остаются в пределах холста', () => {
    for (const layout of ['concentric', 'star', 'circle']) {
        const {svg, width, height} = Soc.render(result, options({layout}));
        for (const m of svg.matchAll(/<circle cx="([\d.-]+)" cy="([\d.-]+)" r="([\d.]+)"/g)) {
            const x = +m[1], y = +m[2], r = +m[3];
            assert.ok(x - r >= 0 && x + r <= width && y - r >= 0 && y + r <= height,
                layout + ': узел вышел за границы холста (' + x + ',' + y + ')');
        }
    }
});

check('подписи выключаются', () => {
    const withNames = Soc.render(result, options({names: true})).svg;
    const without = Soc.render(result, options({names: false})).svg;
    const labels = s => (s.match(/<text[^>]*text-anchor="middle">/g) || []).length;
    assert.ok(labels(withNames) > labels(without), 'подписи не исчезли');
});

check('SVG не содержит незакрытых тегов узлов и связей', () => {
    const {svg} = Soc.render(result, options({antipathy: true}));
    const opened = (svg.match(/<g\b/g) || []).length;
    const closed = (svg.match(/<\/g>/g) || []).length;
    assert.strictEqual(opened, closed, 'непарные <g>');
    const paths = (svg.match(/<path\b/g) || []).length;
    const pathEnds = (svg.match(/\/>/g) || []).length;
    assert.ok(pathEnds >= paths, 'некоторые <path> не самозакрыты');
    assert.ok(svg.startsWith('<svg') && svg.trim().endsWith('</svg>'), 'SVG не обёрнут');
});

check('один участник и пустая матрица не ломают отрисовку', () => {
    const tiny = Object.assign({}, result, {
        n: 1,
        participants: [Object.assign({}, result.participants[0], {received: 0, given: 0, category: 'OUTSIDER'})],
        matrix: [[0]], antiMatrix: [[0]], mutualPairs: [], unidirectionalPairs: []
    });
    for (const layout of ['concentric', 'star', 'circle']) {
        const {svg} = Soc.render(tiny, options({layout}));
        assert.strictEqual(countOf(svg, NODES), 1, layout);
    }
});

check('пустой опрос даёт пустую схему без ошибок', () => {
    const empty = Object.assign({}, result, {
        n: 0, participants: [], matrix: [], antiMatrix: [], mutualPairs: [], unidirectionalPairs: []
    });
    const {svg} = Soc.render(empty, options({layout: 'concentric', antipathy: true}));
    assert.strictEqual(countOf(svg, NODES), 0);
    assert.strictEqual(countOf(svg, LINKS), 0);
});

check('большой класс (30 человек) рисуется без наложений и за границами', () => {
    const big = synthetic(30, 0.7);
    for (const layout of ['concentric', 'star', 'circle']) {
        const {svg, width, height} = Soc.render(big, options({layout}));
        const points = nodes(svg);
        assert.strictEqual(points.length, 30, layout);
        assertNoOverlap(points, layout, big);
        assertInside(points, width, height, layout);
    }
});

check('очень большая группа (80 человек) не ломает схему', () => {
    const big = synthetic(80, 0.3);
    for (const layout of ['concentric', 'star', 'circle']) {
        const {svg, width, height} = Soc.render(big, options({layout}));
        assert.strictEqual(nodes(svg).length, 80, layout);
        assertNoOverlap(nodes(svg), layout, big);
        assertInside(nodes(svg), width, height, layout);
    }
});

check('все участники одной категории не сливаются в одну точку', () => {
    const flat = synthetic(10, 1);
    flat.participants.forEach(p => {
        p.category = 'PREFERRED';
        p.received = 2;
    });
    const {svg} = Soc.render(flat, options({layout: 'concentric'}));
    assertNoOverlap(nodes(svg), 'concentric/одна категория', flat);
});

check('выгрузка несёт стили внутри SVG, а страница берёт их из styles.css', () => {
    // Регрессия: скачанный SVG открывают без styles.css, где описаны
    // .soc-link, .soc-node, .soc-ring. Без них у стрелок не было `fill: none`,
    // и каждая дуга заливалась чёрным — картинка получалась нечитаемой.
    const plain = Soc.render(result, options()).svg;
    const file = Soc.render(result, options({standalone: true})).svg;

    assert.ok(!/<style>/.test(plain),
        'живая страница получила лишний <style>: на экране стили уже есть в styles.css');
    assert.ok(/<style>/.test(file), 'в выгруженном SVG нет блока стилей');

    // Без этих правил файл нечитаем: fill:none убирает чёрные пятна на стрелках,
    // а stroke и обводка возвращают сами линии, кольца и контуры узлов.
    for (const rule of [
        '.soc-link{fill:none}',
        '.soc-link.mutual{stroke:#2f6fd0',
        '.soc-link.single{stroke:#a9bddb',
        '.soc-link.anti{stroke:#c0392b',
        '.soc-node circle{stroke:#33415c',
        '.soc-node text{font-size:11px',
        '.soc-ring{fill:none;stroke:#dde3ec'
    ]) {
        assert.ok(file.includes(rule), 'в выгруженном SVG нет правила ' + rule);
    }

    // Внутри <style> нельзя оставлять «<», «>» и «&»: они сломают разбор XML.
    // Имена свойств вроде stroke и fill внутри правил — обычное дело.
    const css = file.match(/<style>([^<]*)<\/style>/)[1];
    assert.ok(!/[<>&]/.test(css),
        'внутри <style> есть символы, которые сломают разбор XML: ' + css);
    assert.ok(file.indexOf('<style>') < file.indexOf('<defs>'),
        'блок стилей должен идти до <defs>, иначе он не применится');
    assert.ok(file.startsWith('<svg') && file.trim().endsWith('</svg>'), 'SVG не обёрнут');
});

console.log('\n' + passed + ' проверок пройдено');
