(() => {
    const mode = document.getElementById('periodMode');
    const fixed = document.getElementById('fixedPeriodFields');
    if (!mode || !fixed) return;

    const updatePeriod = () => {
        const useFixedPeriod = mode.value === 'FIXED';
        fixed.hidden = !useFixedPeriod;
        fixed.disabled = !useFixedPeriod;
        fixed.querySelectorAll('input').forEach(input => { input.required = useFixedPeriod; });
    };
    mode.addEventListener('change', updatePeriod);
    // Restore the matching controls when the browser restores form values from history.
    window.addEventListener('pageshow', updatePeriod);
    updatePeriod();
})();
