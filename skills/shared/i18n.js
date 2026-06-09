const STRINGS = {
  en: {
    'common.retry': 'Retry',
    'error.generic': 'Something went wrong',
    'error.placesUnavailable': 'Places unavailable — check your connection',
    'error.clipboard': 'Couldn\u2019t copy \u2014 select the text manually',
    'entry.delete': 'Delete entry',
    'entry.deleteConfirm': 'Delete the mood entry for {date}? Its score and note will be permanently removed. This cannot be undone.',
    'nav.prevDay': 'Previous day',
    'nav.nextDay': 'Next day',
    'mood.viewRaw': 'View raw data (developer)',
    'mood.hideRaw': 'Hide raw data',
    'mood.copy': 'Copy',
    'mood.copied': 'Copied!',
    'mood.noEntry': 'No entry for this date.',
    'mood.feelingZen': 'Feeling Zen.',
    'mood.noNotes': 'No notes.',
    'mood.journeyStart': 'Your journey starts here. Log your first mood!',
    'mood.scoreOutOf': 'Score: {score} out of 10',
    'mood.noScore': 'No mood logged for this date',
    'mood.errorTitle': 'Couldn’t load your mood data',
    'common.loading': 'Loading…',
    'common.dismiss': 'Dismiss',
    'roulette.title': '{cuisine} spots in {location}',
    'roulette.spin': 'Spin roulette',
    'roulette.spinning': 'Spinning\u2026',
    'roulette.spinAgain': 'Spin again',
    'roulette.winner': 'Winner!',
    'roulette.emptyTitle': 'No restaurants found',
    'roulette.emptyDesc': 'Try a different cuisine or location.',
    'roulette.errorTitle': 'Couldn\u2019t load the restaurant list',
    'roulette.errorDesc': 'The data could not be read. Close this view and spin again.',
    'roulette.setupTitle': 'Set up required',
    'roulette.setupDesc': 'Add your Gemini API key in the app settings, then try again.',
    'piano.title': '88-key virtual piano',
    'piano.keys': 'Piano keys',
    'piano.scrollHint': '\u27f7 Slide here to scroll \u27f7',
    'piano.tapToEnable': 'Tap anywhere to enable audio',
    'music.title': 'Mood music player',
    'music.play': 'Play',
    'music.pause': 'Pause',
    'music.seek': 'Seek position',
    'music.errorTitle': 'Couldn\u2019t play this track',
    'music.retry': 'Try again',
    'music.noTrack': 'No track available for this vibe yet.',
    'music.noTrackTitle': 'No track',
    'spinner.title': 'Face label tracker',
    'spinner.loading': 'Starting camera and face tracker\u2026',
    'spinner.errorTitle': 'Camera or tracker failed to start',
  },
  es: {
    'common.retry': 'Reintentar',
    'error.generic': 'Algo salió mal',
    'error.placesUnavailable': 'Lugares no disponibles \u2014 revisa tu conexión',
    'error.clipboard': 'No se pudo copiar \u2014 selecciona el texto manualmente',
    'entry.delete': 'Eliminar entrada',
    'entry.deleteConfirm': '¿Eliminar la entrada de ánimo del {date}? Su puntuación y nota se eliminarán de forma permanente. Esta acción no se puede deshacer.',
    'nav.prevDay': 'Día anterior',
    'nav.nextDay': 'Día siguiente',
    'mood.viewRaw': 'Ver datos sin procesar (desarrollador)',
    'mood.hideRaw': 'Ocultar datos sin procesar',
    'mood.copy': 'Copiar',
    'mood.copied': '¡Copiado!',
    'mood.noEntry': 'No hay entrada para esta fecha.',
    'mood.feelingZen': 'Sintiendo Zen.',
    'mood.noNotes': 'Sin notas.',
    'mood.journeyStart': '¡Tu viaje comienza aquí. ¡Registra tu primer estado de ánimo!',
    'mood.scoreOutOf': 'Puntuación: {score} de 10',
    'mood.noScore': 'No hay ánimo registrado para esta fecha',
    'mood.errorTitle': 'No se pudieron cargar tus datos de ánimo',
    'common.loading': 'Cargando…',
    'common.dismiss': 'Descartar',
    'roulette.title': 'Lugares de {cuisine} en {location}',
    'roulette.spin': 'Girar la ruleta',
    'roulette.spinning': 'Girando\u2026',
    'roulette.spinAgain': 'Girar de nuevo',
    'roulette.winner': '¡Ganador!',
    'roulette.emptyTitle': 'No se encontraron restaurantes',
    'roulette.emptyDesc': 'Prueba otra cocina u otra ubicación.',
    'roulette.errorTitle': 'No se pudo cargar la lista de restaurantes',
    'roulette.errorDesc': 'No se pudieron leer los datos. Cierra esta vista y gira de nuevo.',
    'roulette.setupTitle': 'Configuración necesaria',
    'roulette.setupDesc': 'Añade tu clave de API de Gemini en los ajustes y vuelve a intentarlo.',
    'piano.title': 'Piano virtual de 88 teclas',
    'piano.keys': 'Teclas del piano',
    'piano.scrollHint': '\u27f7 Desliza aquí para desplazarte \u27f7',
    'piano.tapToEnable': 'Toca en cualquier lugar para activar el audio',
    'music.title': 'Reproductor de música según el ánimo',
    'music.play': 'Reproducir',
    'music.pause': 'Pausar',
    'music.seek': 'Buscar posición',
    'music.errorTitle': 'No se pudo reproducir esta pista',
    'music.retry': 'Intentar de nuevo',
    'music.noTrack': 'Aún no hay pista disponible para este ánimo.',
    'music.noTrackTitle': 'Sin pista',
    'spinner.title': 'Rastreador de etiqueta facial',
    'spinner.loading': 'Iniciando cámara y rastreador facial…',
    'spinner.errorTitle': 'La cámara o el rastreador no se iniciaron',
  },
};

const lang = (() => {
  const q = new URLSearchParams(location.search).get('lang');
  if (q) return q.split('-')[0];
  const bridge = window.AndroidBridge;
  if (bridge && typeof bridge.getLocale === 'function') {
    const hostLang = bridge.getLocale();
    if (hostLang) return hostLang.split('-')[0];
  }
  return (navigator.language || 'en').split('-')[0];
})();

export function t(key, vars) {
  const raw = STRINGS[lang]?.[key] ?? STRINGS.en[key] ?? key;
  if (!vars) return raw;
  return raw.replace(/\{(\w+)\}/g, (m, name) => (name in vars ? String(vars[name]) : m));
}

// Hydrate static markup: data-i18n -> textContent, data-i18n-title -> title,
// data-i18n-aria -> aria-label. Call once after DOM is ready.
export function hydrate(root = document) {
  root.querySelectorAll('[data-i18n]').forEach((el) => {
    el.textContent = t(el.getAttribute('data-i18n'));
  });
  root.querySelectorAll('[data-i18n-title]').forEach((el) => {
    el.setAttribute('title', t(el.getAttribute('data-i18n-title')));
  });
  root.querySelectorAll('[data-i18n-aria]').forEach((el) => {
    el.setAttribute('aria-label', t(el.getAttribute('data-i18n-aria')));
  });
}
