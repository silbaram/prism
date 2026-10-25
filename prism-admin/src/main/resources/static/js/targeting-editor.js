(function (root) {
    'use strict';
    const PREFIX = 'prism:v1:';
    class ExactNumber { constructor(raw) { this.raw = raw; } }
    // JSON.parse converts integers and decimals to IEEE doubles. Keep numeric lexemes intact.
    function parseExact(text) {
        if (text.length > 10000) throw Error('규칙은 10,000자 이하여야 합니다.');
        let pos = 0;
        const ws = () => { while (/[\x20\t\r\n]/.test(text[pos] || '\0')) pos++; };
        function value(depth) {
            if (depth > 40) throw Error('규칙이 너무 깊습니다.');
            ws();
            const c = text[pos];
            if (c === '"') {
                const start = pos++;
                while (pos < text.length) {
                    if (text[pos] === '\\') { pos += 2; continue; }
                    if (text[pos++] === '"') return JSON.parse(text.slice(start, pos));
                }
                throw Error('문자열이 닫히지 않았습니다.');
            }
            if (c === '{' || c === '[') {
                pos++;
                const object = c === '{', result = object ? Object.create(null) : [];
                ws();
                const end = object ? '}' : ']';
                if (text[pos] === end) { pos++; return result; }
                while (pos < text.length) {
                    let key;
                    if (object) {
                        ws();
                        if (text[pos] !== '"') throw Error('속성 이름을 확인하세요.');
                        key = value(depth + 1); ws();
                        if (Object.hasOwn(result, key) || text[pos++] !== ':') throw Error('중복 키 또는 잘못된 JSON입니다.');
                    }
                    const item = value(depth + 1);
                    if (object) result[key] = item; else result.push(item);
                    ws();
                    if (text[pos] === end) { pos++; return result; }
                    if (text[pos++] !== ',') throw Error('JSON 구분자를 확인하세요.');
                }
                throw Error('JSON이 닫히지 않았습니다.');
            }
            for (const [token, result] of [['true', true], ['false', false], ['null', null]]) {
                if (text.startsWith(token, pos)) { pos += token.length; return result; }
            }
            const token = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/.exec(text.slice(pos));
            if (!token || token[0].length > 256) throw Error('숫자 또는 JSON 값을 확인하세요.');
            pos += token[0].length;
            return new ExactNumber(token[0]);
        }
        const result = value(0); ws();
        if (pos !== text.length) throw Error('JSON 뒤에 불필요한 값이 있습니다.');
        return result;
    }
    function stringifyExact(value) {
        if (value instanceof ExactNumber) return value.raw;
        if (Array.isArray(value)) return '[' + value.map(stringifyExact).join(',') + ']';
        if (value && typeof value === 'object') return '{' + Object.keys(value).map(k => JSON.stringify(k) + ':' + stringifyExact(value[k])).join(',') + '}';
        return JSON.stringify(value);
    }
    const scalar = v => typeof v === 'string' || typeof v === 'boolean' || v instanceof ExactNumber;
    const scalarType = v => v instanceof ExactNumber ? 'NUMBER' : typeof v === 'boolean' ? 'BOOLEAN' : 'STRING';
    function validateTree(tree) {
        let count = 0;
        function visit(node, depth) {
            if (depth > 16 || ++count > 256) throw Error('규칙은 깊이 16, 노드 256개까지 편집할 수 있습니다.');
            if (!node || typeof node !== 'object' || Array.isArray(node) || node instanceof ExactNumber) throw Error('규칙 객체를 확인하세요.');
            const keys = Object.keys(node);
            for (const kind of ['all', 'any', 'not']) {
                if (!Object.hasOwn(node, kind)) continue;
                if (keys.length !== 1) throw Error('그룹에 추가 필드를 사용할 수 없습니다.');
                if (kind === 'not') visit(node.not, depth + 1);
                else {
                    if (!Array.isArray(node[kind]) || node[kind].length < 1 || node[kind].length > 100) throw Error('그룹은 조건 1–100개가 필요합니다.');
                    node[kind].forEach(child => visit(child, depth + 1));
                }
                return;
            }
            if (typeof node.attribute !== 'string' || !node.attribute.trim() || node.attribute.length > 255) throw Error('속성 이름을 확인하세요.');
            if (!['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'in', 'exists'].includes(node.op)) throw Error('지원하지 않는 연산자입니다.');
            if (keys.length !== (node.op === 'exists' ? 2 : 3) || keys.some(k => !['attribute', 'op', 'value'].includes(k))) throw Error('규칙 필드를 확인하세요.');
            if (node.op === 'exists') return;
            if (node.op === 'in') {
                if (!Array.isArray(node.value) || node.value.length < 1 || node.value.length > 100 || !node.value.every(scalar)) throw Error('목록은 문자열·숫자·불리언 1–100개가 필요합니다.');
                if (!node.value.every(v => scalarType(v) === scalarType(node.value[0]))) throw Error('서로 다른 자료형의 목록은 고급 입력에서 편집하세요.');
            } else {
                if (!scalar(node.value)) throw Error('값의 자료형을 확인하세요.');
                if (!['eq', 'ne'].includes(node.op) && !(node.value instanceof ExactNumber)) throw Error('대소 비교에는 숫자가 필요합니다.');
            }
        }
        visit(tree, 0); return tree;
    }
    function parseRule(expression) {
        if (!expression.startsWith(PREFIX)) throw Error('기존 SpEL·미지원 버전은 고급 입력에서 원문을 유지합니다.');
        return validateTree(parseExact(expression.slice(PREFIX.length)));
    }
    const api = { ExactNumber, parseExact, stringifyExact, validateTree, parseRule };
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    if (!root.document) return;

    const doc = root.document;
    const create = (tag, cls, text) => { const e = doc.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
    const button = (text, action) => { const e = create('button', 'btn btn-sm btn-outline-secondary', text); e.type = 'button'; e.addEventListener('click', action); return e; };
    function select(options, selected) {
        const e = create('select', 'form-select form-select-sm');
        options.forEach(([value, label]) => { const o = create('option', '', label); o.value = value; e.append(o); });
        e.value = selected; return e;
    }
    const newLeaf = () => ({attribute: 'country', op: 'eq', value: 'KR'});
    let catalog = [];
    function editorNode(initial, depth) {
        const box = create('div', 'border rounded p-2 mb-2 targeting-node');
        const kind = Object.hasOwn(initial, 'all') ? 'all' : Object.hasOwn(initial, 'any') ? 'any' : Object.hasOwn(initial, 'not') ? 'not' : 'leaf';
        const kindSelect = select([['leaf', '속성 조건'], ['all', '모두 일치 (AND)'], ['any', '하나 이상 일치 (OR)'], ['not', '조건 반전 (NOT)']], kind);
        kindSelect.setAttribute('aria-label', '조건 종류');
        box.append(kindSelect);
        let read;
        function render(node, selected) {
            while (box.children.length > 1) box.lastChild.remove();
            if (selected !== 'leaf') {
                const children = create('div', 'ms-2 mt-2'); box.append(children);
                const append = child => {
                    const row = create('div'); const childEditor = editorNode(child, depth + 1);
                    row.append(childEditor.box);
                    if (selected !== 'not') row.append(button('조건 삭제', () => row.remove()));
                    row.read = childEditor.read; children.append(row);
                };
                (selected === 'not' ? [node.not || newLeaf()] : node[selected] || [newLeaf()]).forEach(append);
                if (selected !== 'not') box.append(button('+ 하위 조건', () => {
                    if (children.children.length >= 100 || depth >= 16) return;
                    append(newLeaf());
                }));
                read = () => ({[selected]: selected === 'not' ? children.firstChild.read() : [...children.children].map(c => c.read())});
            } else {
                const row = create('div', 'row g-2 mt-1');
                if (/[\r\n]/.test(node.attribute || '')) throw Error('줄바꿈이 포함된 속성 이름은 고급 입력에서 원문을 유지합니다.');
                const attr = create('input', 'form-control form-control-sm'); attr.value = node.attribute || ''; attr.placeholder = '속성 이름'; attr.maxLength = 255; attr.setAttribute('aria-label', '속성 이름'); attr.setAttribute('list', 'targetingAttributeOptions');
                const inferred = node.op === 'in' ? scalarType(node.value[0]) : node.op === 'exists' ? (catalog.find(a => a.name === node.attribute)?.type || 'STRING') : scalarType(node.value);
                const type = select([['STRING', '문자열'], ['NUMBER', '숫자'], ['BOOLEAN', '불리언']], inferred);
                type.setAttribute('aria-label', '값 자료형');
                const op = create('select', 'form-select form-select-sm'); op.setAttribute('aria-label', '연산자');
                const input = create('textarea', 'form-control form-control-sm'); input.rows = 2; input.setAttribute('aria-label', '조건 값');
                const originalValue = node.op === 'in' ? stringifyExact(node.value) : node.value instanceof ExactNumber ? node.value.raw : node.value === undefined ? '' : String(node.value);
                input.value = originalValue;
                // Textareas normalize CR/CRLF to LF. Preserve the literal when its displayed
                // contents were not changed, including when another condition was edited.
                const displayedValue = input.value;
                const valueText = () => input.value === displayedValue ? originalValue : input.value;
                const ops = [['eq', '같음'], ['ne', '다름'], ['gt', '초과'], ['gte', '이상'], ['lt', '미만'], ['lte', '이하'], ['in', '목록에 포함'], ['exists', '존재함']];
                function updateOps(chosen) {
                    op.replaceChildren();
                    ops.filter(([v]) => type.value === 'NUMBER' || !['gt', 'gte', 'lt', 'lte'].includes(v)).forEach(([v, label]) => { const o = create('option', '', label); o.value = v; op.append(o); });
                    op.value = [...op.options].some(o => o.value === chosen) ? chosen : 'eq';
                    input.hidden = op.value === 'exists';
                    input.placeholder = op.value === 'in' ? 'JSON 목록: ["KR","US"]' : type.value === 'BOOLEAN' ? 'true 또는 false' : '값';
                }
                type.addEventListener('change', () => updateOps(op.value));
                attr.addEventListener('change', () => { const item = catalog.find(a => a.name === attr.value); if (item) { type.value = item.type; updateOps(op.value); } });
                op.addEventListener('change', () => updateOps(op.value)); updateOps(node.op || 'eq');
                for (const [control, width] of [[attr, 4], [type, 2], [op, 3], [input, 3]]) { const col = create('div', 'col-md-' + width); col.append(control); row.append(col); }
                box.append(row);
                read = () => {
                    const result = {attribute: attr.value, op: op.value};
                    if (op.value === 'exists') return result;
                    let value;
                    if (op.value === 'in') {
                        value = parseExact(valueText());
                        if (!Array.isArray(value) || !value.every(v => scalarType(v) === type.value)) throw Error('목록의 자료형을 확인하세요.');
                    } else if (type.value === 'STRING') value = valueText();
                    else { value = parseExact(valueText()); if (scalarType(value) !== type.value || !scalar(value)) throw Error('값의 자료형을 확인하세요.'); }
                    return {...result, value};
                };
            }
        }
        render(initial, kind);
        kindSelect.addEventListener('change', () => render(kindSelect.value === 'leaf' ? newLeaf() : {}, kindSelect.value));
        return {box, read: () => read()};
    }
    const instances = new Set();
    function attach(item) {
        if (item.dataset.targetingEditor) return;
        item.dataset.targetingEditor = 'true';
        const raw = item.querySelector('input[name*=".expression"]');
        if (!raw || raw.readOnly) return;
        raw.maxLength = 10000;
        const toolbar = create('div', 'd-flex gap-2 my-2'), host = create('div'), message = create('p', 'small text-muted');
        message.setAttribute('role', 'status');
        raw.parentElement.append(toolbar, host, message);
        let visual = false, tree;
        function open() {
            try {
                if (visual) sync();
                const node = raw.value.trim() === '' ? newLeaf() : parseRule(raw.value);
                tree = editorNode(node, 0); host.replaceChildren(tree.box); raw.hidden = true; visual = true; message.textContent = '조건 편집: 모든 최상위 규칙은 AND로 평가됩니다.';
            } catch (e) { message.textContent = e.message; }
        }
        function sync() {
            if (!visual) return;
            const result = PREFIX + stringifyExact(validateTree(tree.read()));
            if (result.length > 10000) throw Error('규칙은 10,000자 이하여야 합니다.');
            raw.value = result;
        }
        toolbar.append(button('조건 편집', open), button('고급 입력', () => {
            try { sync(); visual = false; raw.hidden = false; host.replaceChildren(); message.textContent = '데이터 규칙 또는 기존 SpEL 원문을 입력합니다.'; }
            catch (e) { message.textContent = e.message; }
        }));
        const instance = {sync, item, error: text => { message.textContent = text; }}; instances.add(instance);
        // Do not turn a blank rule into a condition merely by visiting an experiment form.
        if (raw.value.trim()) open();
    }
    function syncAll() {
        let valid = true;
        instances.forEach(instance => {
            if (!instance.item.isConnected) { instances.delete(instance); return; }
            try { instance.sync(); } catch (e) { instance.error(e.message); valid = false; }
        });
        return valid;
    }
    function init() {
        const container = doc.getElementById('targetingRulesContainer');
        if (!container) return;
        const form = doc.getElementById('experimentForm');
        container.querySelectorAll('.rule-item').forEach(attach);
        new MutationObserver(() => container.querySelectorAll('.rule-item').forEach(attach)).observe(container, {childList: true});
        form.addEventListener('submit', e => { if (!syncAll()) { e.preventDefault(); e.stopImmediatePropagation(); } }, true);
        const options = create('datalist'); options.id = 'targetingAttributeOptions'; form.append(options);
        const script = doc.querySelector('script[data-targeting-attributes-url]');
        fetch(script.dataset.targetingAttributesUrl, {headers: {'Accept': 'application/json'}}).then(r => { if (!r.ok) throw Error(); return r.json(); }).then(items => {
            catalog = items;
            items.forEach(a => { const option = create('option'); option.value = a.name; option.label = a.type + ' · ' + a.description; options.append(option); });
        }).catch(() => { doc.getElementById('targetingPreviewResult').textContent = '속성 목록을 불러오지 못했습니다. 이름·자료형을 직접 지정할 수 있습니다.'; });
        let previewSequence = 0;
        doc.getElementById('previewTargeting').addEventListener('click', async () => {
            const sequence = ++previewSequence;
            const result = doc.getElementById('targetingPreviewResult');
            if (!syncAll()) { result.textContent = '조건 입력 오류를 확인하세요.'; return; }
            result.textContent = '확인 중…';
            const expressions = [...container.querySelectorAll('input[name*=".expression"]')].map(e => e.value);
            const csrf = form.querySelector('input[name="_csrf"]');
            try {
                const response = await fetch(script.dataset.targetingPreviewUrl, {method: 'POST', headers: {'Content-Type': 'application/json', ...(csrf ? {'X-CSRF-TOKEN': csrf.value} : {})},
                    body: JSON.stringify({expressions, attributes: doc.getElementById('targetingSample').value})});
                let data;
                try { data = await response.json(); }
                catch (_) { throw Error('로그인 상태와 연결을 확인한 뒤 다시 시도하세요.'); }
                if (sequence !== previewSequence) return;
                if (!response.ok) throw Error(data.message || '타기팅 평가에 실패했습니다.');
                result.textContent = (data.matched ? '타기팅 일치' : '타기팅 불일치') + ' · ' + data.rules.map((match, i) => '규칙 ' + (i + 1) + ': ' + (match ? '일치' : '불일치')).join(', ');
            } catch (e) { if (sequence === previewSequence) result.textContent = e.message || '타기팅 평가에 실패했습니다.'; }
        });
    }
    if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', init); else init();
})(typeof window === 'undefined' ? globalThis : window);
