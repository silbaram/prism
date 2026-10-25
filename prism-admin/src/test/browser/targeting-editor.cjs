// Opt-in DOM regressions: requires Playwright and a local Chromium installation.
const {test, before, after} = require('node:test');
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const path = require('node:path');

let browser;
before(async () => {
    browser = await chromium.launch({executablePath: process.env.PRISM_TEST_CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox']});
});
after(async () => { await browser?.close(); });

async function fixture(tree, check) {
    const page = await browser.newPage();
    try {
        await page.setContent('<form id="experimentForm"><div id="targetingRulesContainer"><div class="rule-item"><div><input name="targetingRules[].expression"></div></div></div><textarea id="targetingSample">{}</textarea><button type="button" id="previewTargeting">preview</button><p id="targetingPreviewResult"></p><script data-targeting-attributes-url="/attributes" data-targeting-preview-url="/preview"></script></form>');
        const expression = 'prism:v1:' + JSON.stringify(tree);
        await page.locator('input[name*=".expression"]').fill(expression);
        await page.evaluate(() => { window.fetch = async () => ({ok: true, json: async () => []}); });
        await page.addScriptTag({path: path.resolve(__dirname, '../../main/resources/static/js/targeting-editor.js')});
        const saved = async () => {
            await page.evaluate(() => document.getElementById('experimentForm').dispatchEvent(new Event('submit', {bubbles: true, cancelable: true})));
            const raw = await page.locator('input[name*=".expression"]').inputValue();
            return JSON.parse(raw.slice('prism:v1:'.length));
        };
        await check(page, saved);
    } finally { await page.close(); }
}

for (const [name, value] of [['LF', 'first\nsecond'], ['CRLF', 'first\r\nsecond'], ['CR', 'first\rsecond']]) {
    test(`saving an untouched ${name} string preserves the original value`, async () => {
        const tree = {attribute: 'message', op: 'eq', value};
        await fixture(tree, async (_page, saved) => assert.deepEqual(await saved(), tree));
    });
}

test('changing another condition preserves an untouched CRLF string', async () => {
    const tree = {all: [{attribute: 'message', op: 'eq', value: 'first\r\nsecond'}, {attribute: 'age', op: 'gte', value: 20}]};
    await fixture(tree, async (page, saved) => {
        await page.getByRole('textbox', {name: '조건 값', exact: true}).nth(1).fill('21');
        assert.deepEqual(await saved(), {all: [tree.all[0], {...tree.all[1], value: 21}]});
    });
});

test('an edited string supports line breaks and survives advanced and visual editing', async () => {
    await fixture({attribute: 'message', op: 'eq', value: 'old'}, async (page, saved) => {
        await page.getByRole('textbox', {name: '조건 값', exact: true}).fill('new\nvalue');
        assert.equal((await saved()).value, 'new\nvalue');
        await page.getByRole('button', {name: '고급 입력', exact: true}).click();
        await page.getByRole('button', {name: '조건 편집', exact: true}).click();
        assert.equal((await saved()).value, 'new\nvalue');
    });
});

test('line breaks in attribute names retain the complete advanced expression', async () => {
    const tree = {attribute: 'profile\nname', op: 'eq', value: 'KR'};
    await fixture(tree, async (page, saved) => {
        assert.equal(await page.locator('input[name*=".expression"]').isVisible(), true);
        assert.deepEqual(await saved(), tree);
    });
});
