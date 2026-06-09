import { log } from './log.js';

export function fail(scope, err, userMessage) {
  log.error(`[${scope}]`, err);
  const el = document.getElementById('error');
  if (el) {
    el.textContent = userMessage ?? 'Something went wrong';
    el.hidden = false;
  }
  window.parent?.postMessage({ type: 'skill-error', scope, message: String(err) }, '*');
}

export function ok(value) {
  return { ok: true, value };
}

export function err(error) {
  return { ok: false, error };
}
