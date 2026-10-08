const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const {runInNewContext} = require('node:vm');

function fixture() {
    function element(value = '') {
        const listeners = {};
        return {value, dataset: {}, children: [],
            addEventListener(type, callback) { listeners[type] = callback; },
            emit(type) { return listeners[type]?.(); },
            getAttribute() { return 'options'; },
            replaceChildren(...children) { this.children = children; }};
    }
    const goal = element('purchase'), guardrail = element('failure'), options = element();
    const timers = new Map();
    const pending = [];
    const document = {
        currentScript: {dataset: {suggestionsUrl: '/prism/admin/events/suggestions'}},
        activeElement: goal,
        querySelectorAll(selector) { return selector === '[data-event-search]' ? [goal, guardrail] : []; },
        getElementById() { return options; }, createElement() { return element(); }
    };
    runInNewContext(readFileSync(join(__dirname, '../../main/resources/static/js/event-picker.js'), 'utf8'), {
        document,
        setTimeout(callback) { const id = Symbol(); timers.set(id, callback); return id; },
        clearTimeout(id) { timers.delete(id); },
        fetch(url) { return new Promise(resolve => pending.push({url, resolve})); }
    });
    return {goal, guardrail, options, document, pending,
        tick() { const callbacks = [...timers.values()]; timers.clear(); callbacks.forEach(callback => callback()); },
        async respond(index, names) {
            pending[index].resolve({ok: true, json: async () => ({names})});
            await new Promise(resolve => setImmediate(resolve));
        }
    };
}

test('a pending search from a blurred field cannot replace the focused field suggestions', async () => {
    const f = fixture();
    f.goal.emit('input'); // Debounced search has not started yet.
    f.goal.emit('blur');
    f.document.activeElement = f.guardrail;
    f.guardrail.emit('focus');
    await f.respond(0, ['failure']);
    f.tick();
    assert.equal(f.pending.length, 1, 'the old goal search must not run after moving to guardrails');
    assert.equal(f.options.children[0].value, 'failure');
});

test('an in-flight response is ignored after the field loses focus', async () => {
    const f = fixture();
    f.goal.emit('focus');
    f.document.activeElement = null;
    f.goal.emit('blur');
    await f.respond(0, ['purchase']);
    assert.equal(f.options.children.length, 0);
});

test('only the latest query supplies literal options and keeps the context path', async () => {
    const f = fixture();
    f.goal.emit('focus');
    f.goal.value = 'purchase +';
    f.goal.emit('input'); f.tick();
    assert.equal(f.pending[1].url, '/prism/admin/events/suggestions?q=purchase%20%2B');
    await f.respond(1, ['purchase +', '<script>data</script>']);
    await f.respond(0, ['old']);
    assert.equal(f.options.children[0].value, 'purchase +');
    assert.equal(f.options.children[1].value, '<script>data</script>');
});
