import { authHeaders, getBase } from './api';

// Adds the OPENJARVIS key to every call to our own server (/api and /v1),
// so direct fetch() calls are authenticated like apiFetch().
export function installAuthFetch(): void {
  if (typeof window === 'undefined' || (window as any).__jarvisAuthFetch) return;
  (window as any).__jarvisAuthFetch = true;
  const original = window.fetch.bind(window);
  window.fetch = (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    const base = getBase();
    const path = base && url.startsWith(base) ? url.slice(base.length) : url;
    if (/^\/(api|v1)\//.test(path)) {
      const h = new Headers(init.headers || (input instanceof Request ? input.headers : undefined));
      for (const [k, v] of Object.entries(authHeaders())) if (!h.has(k)) h.set(k, v);
      init = { ...init, headers: h };
    }
    return original(input, init);
  };
}
