'use strict';
/*
 * Точка входа для всех проверок фронтенда: запускает наборы тестов по очереди.
 *
 * Запуск:  node src/test/js/run.js
 */

const path = require('path');
const {execFileSync} = require('child_process');

const suites = ['static.test.js', 'sociogram.test.js', 'app.test.js'];
let failed = 0;

for (const suite of suites) {
    console.log('\n=== ' + suite + ' ===');
    try {
        execFileSync(process.execPath, [path.join(__dirname, suite)], {stdio: 'inherit'});
    } catch (e) {
        failed++;
    }
}

if (failed) {
    console.error('\n' + failed + ' набор(а) провалились');
    process.exit(1);
}
console.log('\nВсе наборы фронтенда прошли');
