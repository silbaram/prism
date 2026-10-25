const test = require('node:test');
const assert = require('node:assert/strict');
const editor = require('../../main/resources/static/js/targeting-editor.js');

test('nested groups round trip without rounding long integers or decimals', () => {
    const text = 'prism:v1:{"all":[{"attribute":"id","op":"eq","value":9007199254740993},{"any":[{"attribute":"amount","op":"gte","value":0.12345678901234567890123456789},{"not":{"attribute":"country","op":"in","value":["KR","US"]}}]}]}';
    assert.equal('prism:v1:' + editor.stringifyExact(editor.parseRule(text)), text);
});

test('unsupported expressions and mixed lists stay in advanced editing', () => {
    for (const expression of ["age >= 20", 'prism:v2:{}', 'prism:v1:{"attribute":"country","op":"in","value":["KR",1]}']) {
        assert.throws(() => editor.parseRule(expression));
    }
});

test('invalid types duplicate fields trailing values and overcomplex rules are rejected', () => {
    for (const json of ['{"attribute":"age","op":"gt","value":"20"}', '{"not":null}', '{"all":[]}',
        '{"attribute":"a","attribute":"b","op":"exists"}', '{"attribute":"a","op":"exists","value":1}',
        '{"attribute":"a","op":"eq","value":01}', '{} true']) {
        assert.throws(() => editor.parseRule('prism:v1:' + json));
    }
    let node = {attribute: 'a', op: 'exists'};
    for (let i = 0; i < 17; i++) node = {not: node};
    assert.throws(() => editor.validateTree(node));
});

test('prototype-like keys and escaped strings are parsed as data', () => {
    const value = editor.parseExact('{"__proto__":"value","text":"a\\\"b"}');
    assert.equal(Object.getPrototypeOf(value), null);
    assert.equal(editor.stringifyExact(value), '{"__proto__":"value","text":"a\\\"b"}');
});
