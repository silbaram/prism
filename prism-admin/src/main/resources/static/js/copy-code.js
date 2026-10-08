document.querySelectorAll('[data-copy-code]').forEach(button => {
    button.addEventListener('click', async () => {
        const code = document.getElementById(button.dataset.copyCode);
        const status = document.getElementById('copyStatus');
        try {
            await navigator.clipboard.writeText(code.textContent);
            status.textContent = '코드를 복사했습니다.';
        } catch (_) {
            const range = document.createRange();
            range.selectNodeContents(code);
            const selection = window.getSelection();
            selection.removeAllRanges();
            selection.addRange(range);
            status.textContent = '코드를 선택했습니다. Ctrl+C 또는 Command+C로 복사하세요.';
        }
    });
});
