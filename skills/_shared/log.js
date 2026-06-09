const LEVEL = new URLSearchParams(location.search).get('debug') ? 0 : 1;

export const log = {
  debug: (...a) => LEVEL <= 0 && console.debug('[skill]', ...a),
  warn:  (...a) => console.warn('[skill]', ...a),
  error: (...a) => {
    console.error('[skill]', ...a);
    window.parent?.postMessage({ type: 'skill-log', level: 'error', args: a.map(String) }, '*');
  },
};
