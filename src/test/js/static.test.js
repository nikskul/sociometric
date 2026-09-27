'use strict';
/*
 * Быстрая проверка качества фронтенда без браузера и без сборщика:
 *   1. синтаксис всех статических файлов;
 *   2. в index.html нет ссылок на несуществующие файлы;
 *   3. в app.js не осталось обращений к несуществующим элементам страницы;
 *   4. в разметке нет незакрытых тегов и дублирующихся id.
 *
 * Запуск:  node src/test/js/static.test.js
 */

const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const STATIC = path.join(__dirname, '..', '..', 'main', 'resources', 'static');
const html = fs.readFileSync(path.join(STATIC, 'index.html'), 'utf8');
const appJs = fs.readFileSync(path.join(STATIC, 'app.js'), 'utf8');
const files = fs.readdirSync(STATIC).filter(f => /\.(js|css|html)$/.test(f));
// сами тесты тоже должны оставаться текстом: сырой NUL внутри строки
// невидим в diff и ломает grep, не ломая node
const ownFiles = fs.readdirSync(__dirname).filter(f => f.endsWith('.js'));

let passed = 0;
const results = [];
function check(name, fn) {
    try {
        fn();
        passed++;
        results.push('  ok  ' + name);
    } catch (e) {
        results.push('FAIL  ' + name + '\n      ' + e.message);
        process.exitCode = 1;
    }
}

check('все файлы читаются как текст, без бинарных байтов', () => {
    const check1 = (name, bytes) => {
        for (const byte of bytes) {
            assert.ok(byte === 9 || byte === 10 || byte === 13 || byte >= 32,
                name + ': непечатаемый байт ' + byte);
        }
    };
    for (const file of files) check1(file, fs.readFileSync(path.join(STATIC, file)));
    for (const file of ownFiles) check1('src/test/js/' + file, fs.readFileSync(path.join(__dirname, file)));
});

check('в статике нет забытых тестовых точек входа', () => {
    const leaks = [...appJs.matchAll(/([A-Za-z_$][\w$]*ForTest)/g)].map(m => m[1]);
    assert.strictEqual(leaks.length, 0, 'в бандле попали тестовые функции: ' + leaks.join(', '));
});

check('js-файлы разбираются', () => {
    for (const file of files.filter(f => f.endsWith('.js'))) {
        const code = fs.readFileSync(path.join(STATIC, file), 'utf8');
        assert.doesNotThrow(() => new vm.Script(code, {filename: file}), file + ' не разбирается');
    }
});

check('в разметке css-классы сбалансированы', () => {
    const opens = (html.match(/{/g) || []).length;
    const closes = (html.match(/}/g) || []).length;
    assert.strictEqual(opens, closes, 'скобки в <style> не сходятся');
});

check('id элементов не повторяются', () => {
    const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map(m => m[1]);
    const seen = new Set();
    for (const id of ids) {
        assert.ok(!seen.has(id), 'id «' + id + '» встречается дважды');
        seen.add(id);
    }
    assert.ok(ids.length > 40, 'подозрительно мало элементов: ' + ids.length);
});

check('index.html ссылается только на существующие файлы', () => {
    for (const m of html.matchAll(/(?:src|href)="([^"#:]+)"/g)) {
        const target = m[1];
        if (/^(https?:|data:|mailto:)/.test(target)) continue;
        assert.ok(fs.existsSync(path.join(STATIC, target)), 'нет файла ' + target);
    }
});

check('app.js обращается только к существующим элементам', () => {
    const ids = new Set([...html.matchAll(/\bid="([^"]+)"/g)].map(m => m[1]));
    const used = new Set([...appJs.matchAll(/\$\('#([A-Za-z0-9_-]+)'\)/g)].map(m => m[1]));
    for (const id of used) {
        assert.ok(ids.has(id), 'в разметке нет элемента с id «' + id + '»');
    }
    assert.ok(used.size > 40, 'подозрительно мало обращений: ' + used.size);
});

check('обработчики в app.js навешены на существующие элементы', () => {
    const ids = new Set([...html.matchAll(/\bid="([^"]+)"/g)].map(m => m[1]));
    for (const m of appJs.matchAll(/\$\('#([A-Za-z0-9_-]+)'\)\.addEventListener/g)) {
        assert.ok(ids.has(m[1]), 'обработчик навешен на несуществующий #' + m[1]);
    }
});

check('в app.js нет console.log — отладочный вывод в продакшен не нужен', () => {
    const matches = appJs.match(/console\.(log|debug|dir)\(/g) || [];
    assert.strictEqual(matches.length, 0, 'найдены отладочные вызовы: ' + matches.join(', '));
});

check('в css нет правил для классов, которых не существует', () => {
    // классы, которые разметка и скрипты реально используют
    const used = new Set();
    for (const m of html.matchAll(/class="([^"]+)"/g)) m[1].split(/\s+/).forEach(c => used.add(c));
    for (const m of appJs.matchAll(/class:\s*'([^']+)'/g)) m[1].split(/\s+/).forEach(c => used.add(c));
    for (const m of appJs.matchAll(/classList\.(?:add|toggle|remove)\('([^']+)'/g)) used.add(m[1]);
    for (const m of appJs.matchAll(/badge-([A-Z][A-Z_]*)/g)) used.add('badge-' + m[1]);
    for (const m of appJs.matchAll(/'soc-link ([a-z]+)'/g)) used.add('soc-link');
    for (const m of appJs.matchAll(/'soc-node ([a-z]+)'/g)) used.add('soc-node');

    const css = fs.readFileSync(path.join(STATIC, 'styles.css'), 'utf8');
    const defined = new Set([...css.matchAll(/\.([a-zA-Z][\w-]*)/g)].map(m => m[1]));
    const undefinedInCss = [...used].filter(c => c
        // куски вида 'badge badge-' — это начало склейки в JS, а не класс
        && !c.endsWith('-')
        && !defined.has(c)
        && !c.startsWith('soc-link') && !c.startsWith('soc-node'));
    assert.strictEqual(undefinedInCss.length, 0,
        'используются, но не описаны в css: ' + undefinedInCss.join(', '));
});

check('правила печати скрывают интерфейс, но не результат', () => {
    const print = fs.readFileSync(path.join(STATIC, 'styles.css'), 'utf8')
        .split('@media print')[1] || '';
    assert.ok(print.length > 0, 'нет блока @media print');
    for (const hidden of ['.topbar', '.tabs', '.no-print']) {
        assert.ok(print.includes(hidden), 'при печати виден ' + hidden);
    }
    assert.ok(print.includes('display: none'), 'нет ни одного display:none при печати');
});

check('при печати попадает только открытая вкладка', () => {
    const print = fs.readFileSync(path.join(STATIC, 'styles.css'), 'utf8')
        .split('@media print')[1] || '';
    // .panel делается видимой всем подряд, поэтому скрытая вкладка должна
    // быть явно погашена обратно — иначе на бумагу идут все шесть панелей
    assert.ok(print.includes('.panel.hidden'),
        'нет правила .panel.hidden: печать выведет все вкладки сразу');
    const hidden = print.indexOf('.panel.hidden');
    const shown = print.indexOf('.panel {');
    assert.ok(shown < hidden, 'правило .panel.hidden должно идти после .panel, иначе его перебьёт !important');
});

console.log(results.join('\n'));
console.log('\n' + passed + ' проверок пройдено');
