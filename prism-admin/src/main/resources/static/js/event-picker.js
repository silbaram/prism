// Use DOM text/value APIs: event names are data, including quotes and HTML characters.
const suggestionsUrl = document.currentScript.dataset.suggestionsUrl;
document.querySelectorAll('[data-event-search]').forEach(input => {
    let timer;
    const list = document.getElementById(input.getAttribute('list'));
    const search = async () => {
        if (document.activeElement !== input || input.readOnly || input.disabled) return;
        const query = input.value;
        const sequence = String(Number(list.dataset.sequence || 0) + 1);
        list.dataset.sequence = sequence;
        try {
            const response = await fetch(suggestionsUrl + '?q=' + encodeURIComponent(query), {
                headers: {Accept: 'application/json'}
            });
            if (!response.ok) return;
            const result = await response.json();
            if (document.activeElement !== input || list.dataset.sequence !== sequence || input.value !== query) return;
            list.replaceChildren(...result.names.map(name => {
                const option = document.createElement('option');
                option.value = name;
                return option;
            }));
        } catch (_) { /* Manual event entry remains available if suggestions cannot be loaded. */ }
    };
    input.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(search, 200); });
    input.addEventListener('focus', search);
    input.addEventListener('blur', () => clearTimeout(timer));
});

document.querySelectorAll('[data-append-event]').forEach(button => {
    button.addEventListener('click', () => {
        const input = document.getElementById(button.dataset.appendEvent);
        const target = document.getElementById(button.dataset.eventTarget);
        if (target.readOnly || target.disabled || !input.value.trim()) return;
        if (/[\r\n]/.test(input.value)) {
            input.setCustomValidity('이벤트를 한 줄씩 입력하세요.');
            input.reportValidity();
            return;
        }
        input.setCustomValidity('');
        const steps = target.value.split(/\r?\n/).filter(name => name.trim());
        if (!steps.includes(input.value)) steps.push(input.value);
        target.value = steps.join('\n');
        target.dispatchEvent(new Event('input', {bubbles: true}));
        input.value = '';
    });
});
