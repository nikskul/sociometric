/*
 * Построение социограммы по Я. Морено в SVG.
 *
 * Три классические раскладки:
 *   concentric — круг в круге: звезды в центре, принятые вокруг,
 *                отвергаемые и аутсайдеры на внешнем круге;
 *   star       — «звезда»: лидеры в центре, остальные на орбите;
 *   circle     — все участники на одном круге, упорядоченные по статусу.
 *
 * Файл самодостаточен: ни одной зависимости от DOM, только сборка строки SVG,
 * поэтому его можно проверять обычными тестами в node (src/test/js).
 */
const Soc = (() => {

    const RING_ORDER = ['STAR', 'PREFERRED', 'ACCEPTED', 'ISOLATED', 'REJECTED', 'OUTSIDER'];

    const COLORS = {
        STAR: '#2e9e4f',
        PREFERRED: '#8fd19a',
        ACCEPTED: '#bcd6f5',
        ISOLATED: '#f5c26b',
        REJECTED: '#e88b8b',
        OUTSIDER: '#9aa3b2',
        NO_DATA: '#ccd2dc'
    };

    const TITLES = {
        STAR: 'Звезда',
        PREFERRED: 'Предпочитаемый',
        ACCEPTED: 'Принятый',
        ISOLATED: 'Изолированный',
        REJECTED: 'Отвергаемый',
        OUTSIDER: 'Аутсайдер'
    };

    const RING_TITLES = ['звёзды', 'предпочитаемые', 'принятые', 'изолированные', 'отвергаемые', 'аутсайдеры'];

    const GAP = 6;        // зазор между соседними узлами
    const MARGIN = 34;    // поле вокруг схемы
    const MAX_SIZE = 3000; // предел размера холста

    /**
     * @param result результат из /result
     * @param options {layout, arrows, hide, antipathy, names}
     * @returns {{svg: string, width: number, height: number}}
     */
    function render(result, options) {
        const opts = Object.assign({layout: 'concentric', arrows: 'all', hide: '', antipathy: false, names: true},
                options || {});
        const hidden = new Set((opts.hide || '').split(',').filter(Boolean));
        const people = result.participants.filter(p => !hidden.has(p.category));

        // чем больше группа, тем мельче кружки, иначе схема не влезет на лист
        const base = people.length > 30 ? 10 : people.length > 18 ? 13 : 16;
        const maxNode = base + 6;
        const size = canvasSize(people.length, maxNode);
        const width = size;
        const height = size;
        const cx = width / 2;
        const cy = height / 2;
        const available = size / 2 - MARGIN - maxNode;

        const positions = layout(people, opts.layout, cx, cy, available, maxNode);

        const parts = [];
        parts.push(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}" `
            + `width="${width}" height="${height}" font-family="Segoe UI, Arial, sans-serif">`);
        parts.push(`<defs>
            <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                <path d="M0,0 L10,5 L0,10 z" fill="#2f6fd0"/>
            </marker>
            <marker id="arrow-muted" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
                <path d="M0,0 L10,5 L0,10 z" fill="#a9bddb"/>
            </marker>
            <marker id="arrow-anti" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
                <path d="M0,0 L10,5 L0,10 z" fill="#c0392b"/>
            </marker>
        </defs>`);
        parts.push(`<rect width="${width}" height="${height}" fill="#ffffff"/>`);
        parts.push(`<text x="${cx}" y="${MARGIN - 12}" text-anchor="middle" class="soc-title">`
            + `Социограмма: ${escapeXml(result.name || '')}</text>`);

        const rings = ringRadii(people, available, maxNode);
        if (opts.layout === 'concentric') {
            parts.push(...drawRings(people, rings, cx, cy, width, height));
        }

        // стрелки
        if (opts.arrows !== 'none') {
            parts.push(...sympathyLinks(result, people, positions, maxNode, opts.arrows === 'mutual')
                    .map(pathElement));
        }
        if (opts.antipathy) {
            parts.push(...antipathyLinks(result, people, positions, maxNode).map(pathElement));
        }

        // узлы
        for (const person of people) {
            const p = positions.get(person.code);
            if (!p) continue;
            const r = (base + Math.min(6, person.received * 1.2)).toFixed(1);
            parts.push(`<g class="soc-node" data-code="${escapeXml(person.code)}" tabindex="0">`);
            parts.push(`<title>${escapeXml(person.name)} (${escapeXml(person.code)}): ${person.categoryTitle}, `
                + `получено выборов — ${person.received}, выбрано — ${person.given}`
                + (person.rejectedBy ? `, антипатий — ${person.rejectedBy}` : '') + `</title>`);
            parts.push(`<circle cx="${p.x.toFixed(1)}" cy="${p.y.toFixed(1)}" r="${r}" fill="${colorOf(person)}"/>`);
            if (opts.names) {
                parts.push(`<text x="${p.x.toFixed(1)}" y="${(p.y - r - 4).toFixed(1)}" text-anchor="middle">`
                    + `${escapeXml(person.code)}. ${escapeXml(shortName(person.name))}</text>`);
            }
            parts.push('</g>');
        }
        parts.push('</svg>');
        return {svg: parts.join(''), width, height};
    }

    // ------------------------------------------------------------------ раскладки

    function layout(people, mode, cx, cy, available, maxNode) {
        if (mode === 'circle') {
            return placeOnCircle(people, new Map(), cx, cy, available, maxNode);
        }
        if (mode === 'star') {
            return placeStar(people, new Map(), cx, cy, available, maxNode);
        }
        return placeConcentric(people, new Map(), cx, cy, available, maxNode);
    }

    /**
     * Радиусы колец: каждый круг должен вмещать своих участников по окружности
     * и не наезжать на соседний круг. Так узлы никогда не сливаются.
     */
    function ringRadii(people, available, maxNode) {
        const radii = new Array(RING_ORDER.length).fill(null);
        const buckets = RING_ORDER.map(c => people.filter(p => p.category === c));
        const occupied = [];
        buckets.forEach((bucket, index) => {
            if (bucket.length) occupied.push({index, count: bucket.length});
        });
        if (!occupied.length) return radii;

        const step = 2 * maxNode + GAP;
        const computed = [];
        let previous = 0;
        occupied.forEach((ring, i) => {
            // минимальный радиус, при котором участники кольца не сталкиваются
            const needed = (ring.count * step) / (2 * Math.PI);
            let r;
            if (i === 0) {
                r = ring.count <= 1 ? 0 : Math.max(needed, step);
            } else {
                r = Math.max(previous + step, needed);
            }
            computed.push({index: ring.index, radius: r, count: ring.count});
            previous = r;
        });

        // остаток места равномерно распределяем между кольцами — схема заполняет холст
        const spare = Math.max(0, available - previous);
        if (spare > 0) {
            const share = spare / computed.length;
            let base = 0;
            for (const ring of computed) {
                ring.radius += base;
                base += share;
            }
        }
        for (const ring of computed) {
            radii[ring.index] = ring.radius;
        }
        return radii;
    }

    /** Круг в круге: категории расходятся концентрическими окружностями от центра. */
    function placeConcentric(people, positions, cx, cy, available, maxNode) {
        const radii = ringRadii(people, available, maxNode);
        RING_ORDER.forEach((category, ringIndex) => {
            const bucket = people.filter(p => p.category === category);
            if (!bucket.length) return;
            const r = radii[ringIndex] || 0;
            // сдвиг угла по кольцам, чтобы стрелки между кольцами не совпадали
            const offset = ringIndex * 0.4;
            bucket.forEach((person, i) => {
                const angle = (2 * Math.PI * i) / bucket.length - Math.PI / 2 + offset;
                positions.set(person.code, {x: cx + r * Math.cos(angle), y: cy + r * Math.sin(angle)});
            });
        });
        return positions;
    }

    /** Звезда: лидеры тесным ядром, остальные на одной орбите. */
    function placeStar(people, positions, cx, cy, available, maxNode) {
        const leaders = people.filter(p => p.category === 'STAR' || p.category === 'PREFERRED');
        const others = people.filter(p => !(p.category === 'STAR' || p.category === 'PREFERRED'));
        const step = 2 * maxNode + GAP;

        const core = leaders.length <= 1
            ? 0
            : Math.max((leaders.length * step) / (2 * Math.PI), step);
        leaders.forEach((person, i) => {
            const angle = (2 * Math.PI * i) / Math.max(1, leaders.length) - Math.PI / 2;
            positions.set(person.code, {x: cx + core * Math.cos(angle), y: cy + core * Math.sin(angle)});
        });

        const orbit = Math.max(core + step, (others.length * step) / (2 * Math.PI), 1);
        others.forEach((person, i) => {
            const angle = (2 * Math.PI * i) / Math.max(1, others.length) - Math.PI / 2;
            positions.set(person.code, {x: cx + orbit * Math.cos(angle), y: cy + orbit * Math.sin(angle)});
        });
        return positions;
    }

    /** Все на одном круге, по часовой стрелке от лучших к худшим. */
    function placeOnCircle(people, positions, cx, cy, available, maxNode) {
        const step = 2 * maxNode + GAP;
        const radius = Math.max(available, (people.length * step) / (2 * Math.PI), step);
        const sorted = [...people].sort((a, b) => b.received - a.received);
        sorted.forEach((person, i) => {
            const angle = (2 * Math.PI * i) / Math.max(1, sorted.length) - Math.PI / 2;
            positions.set(person.code, {x: cx + radius * Math.cos(angle), y: cy + radius * Math.sin(angle)});
        });
        return positions;
    }

    function drawRings(people, radii, cx, cy, width, height) {
        const out = [`<circle cx="${cx}" cy="${cy}" r="${(width / 2 - MARGIN).toFixed(1)}" class="soc-ring"/>`];
        RING_ORDER.forEach((category, index) => {
            const r = radii[index];
            if (r === null || index === 0) return;
            out.push(`<circle cx="${cx}" cy="${cy}" r="${r.toFixed(1)}" class="soc-ring"/>`);
            out.push(`<text x="${cx + 4}" y="${(cy - r + 12).toFixed(1)}" class="soc-ring-label">`
                + `${RING_TITLES[index]} (${people.filter(p => p.category === category).length})</text>`);
        });
        return out;
    }

    // ------------------------------------------------------------------ стрелки

    /**
     * Взаимная пара рисуется одной линией с двумя наконечниками, односторонняя —
     * одной стрелкой. Поэтому линий ровно столько же, сколько связей в матрице.
     */
    function sympathyLinks(result, people, positions, maxNode, onlyMutual) {
        const links = [];
        const n = result.matrix.length;
        for (let i = 0; i < n; i++) {
            for (let j = i + 1; j < n; j++) {
                const forward = result.matrix[i][j] > 0;
                const back = result.matrix[j][i] > 0;
                if (!forward && !back) continue;
                const mutual = forward && back;
                if (onlyMutual && !mutual) continue;
                const a = positions.get(people[i] ? people[i].code : null);
                const b = positions.get(people[j] ? people[j].code : null);
                if (!a || !b) continue;
                links.push({
                    d: linkPath(a, b, maxNode),
                    kind: mutual ? 'mutual' : 'single',
                    marker: mutual ? 'arrow' : 'arrow-muted',
                    doubleHeaded: mutual,
                    from: people[i].code,
                    to: people[j].code
                });
            }
        }
        return links;
    }

    function antipathyLinks(result, people, positions, maxNode) {
        const links = [];
        const n = result.antiMatrix.length;
        for (let i = 0; i < n; i++) {
            for (let j = 0; j < n; j++) {
                if (i === j || result.antiMatrix[i][j] <= 0) continue;
                const a = positions.get(people[i] ? people[i].code : null);
                const b = positions.get(people[j] ? people[j].code : null);
                if (!a || !b) continue;
                links.push({d: linkPath(a, b, maxNode), kind: 'anti', marker: 'arrow-anti', from: people[i].code, to: people[j].code});
            }
        }
        return links;
    }

    /** Дуга между узлами, подрезанная по радиусу, чтобы стрелка не лезла внутрь кружка. */
    function linkPath(a, b, maxNode) {
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const distance = Math.hypot(dx, dy) || 1;
        const trim = maxNode + 3;
        const x1 = a.x + (dx / distance) * trim;
        const y1 = a.y + (dy / distance) * trim;
        const x2 = b.x - (dx / distance) * trim;
        const y2 = b.y - (dy / distance) * trim;
        // лёгкий изгиб — соседние стрелки не накладываются друг на друга
        const mx = (x1 + x2) / 2;
        const my = (y1 + y2) / 2;
        const bend = distance < 2.5 * maxNode ? 0.16 : 0.08;
        const cx = mx - dy * bend;
        const cy = my + dx * bend;
        return `M${x1.toFixed(1)},${y1.toFixed(1)} Q${cx.toFixed(1)},${cy.toFixed(1)} ${x2.toFixed(1)},${y2.toFixed(1)}`;
    }

    function pathElement(link) {
        const start = link.doubleHeaded ? ` marker-start="url(#${link.marker})"` : '';
        return `<path d="${link.d}" class="soc-link ${link.kind}" marker-end="url(#${link.marker})"${start} `
            + `data-from="${escapeXml(link.from)}" data-to="${escapeXml(link.to)}"/>`;
    }

    // ------------------------------------------------------------------ прочее

    /** Размер холста под группу: чем больше участников, тем больше лист. */
    function canvasSize(count, maxNode) {
        const needed = 2 * ((count * (2 * maxNode + GAP)) / (2 * Math.PI) + maxNode + MARGIN);
        return Math.min(MAX_SIZE, Math.max(980, Math.ceil(needed / 10) * 10));
    }

    function colorOf(person) {
        return COLORS[person.category] || COLORS.NO_DATA;
    }

    function shortName(name) {
        if (!name) return '';
        const trimmed = String(name).trim();
        return trimmed.length > 14 ? trimmed.slice(0, 13) + '…' : trimmed;
    }

    function escapeXml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&apos;');
    }

    return {render, pathElement, COLORS, TITLES, RING_ORDER};
})();
