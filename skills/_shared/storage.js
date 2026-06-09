const HAS_BRIDGE = typeof window.AndroidBridge?.getItem === 'function';

export const storage = {
  get(key) {
    if (HAS_BRIDGE) return window.AndroidBridge.getItem(key);
    return localStorage.getItem(key);
  },
  set(key, value) {
    if (HAS_BRIDGE) window.AndroidBridge.setItem(key, value);
    else localStorage.setItem(key, value);
  },
  remove(key) {
    if (HAS_BRIDGE) window.AndroidBridge.removeItem(key);
    else localStorage.removeItem(key);
  },
};
