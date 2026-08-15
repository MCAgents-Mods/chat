/*
 * Silver Glass — shared chrome loader and console helpers.
 *
 * A page sets two globals in <head> before loading this:
 *   window.SITE_ROOT    relative path back to the console root ("", "../", "../../")
 *   window.PAGE_SECTION which nav link to mark active
 *
 * Everything here is plain DOM. No framework, no bundler, nothing fetched from off the
 * machine — see .agents/design/web-design-system.md.
 */
(function () {
  'use strict';

  /*
   * The console refuses any request without this. It is in the address bar because that is
   * the only place the game could put it, which also means it is in the browser's history —
   * acceptable for a console that only ever listens on loopback, and worth knowing.
   */
  var token = new URLSearchParams(window.location.search).get('t') || '';
  var root = window.SITE_ROOT || '';

  /** A console URL with the token attached, for a link the reader will click. */
  function link(path) {
    return root + path + (path.indexOf('?') === -1 ? '?' : '&') + 't=' + encodeURIComponent(token);
  }

  /**
   * Calls the JSON API.
   *
   * The token travels in a header rather than the query string so that it stays out of the
   * paths that end up in error messages, and so a copied API path is inert on its own.
   */
  async function api(path, options) {
    var settings = options || {};
    var headers = Object.assign({ 'X-MCAgents-Token': token }, settings.headers || {});
    if (settings.body) {
      headers['Content-Type'] = 'application/json';
    }

    var response = await fetch(root + 'api' + path, {
      method: settings.method || 'GET',
      headers: headers,
      body: settings.body ? JSON.stringify(settings.body) : undefined
    });

    var payload = null;
    try {
      payload = await response.json();
    } catch (ignored) {
      // A body that is not JSON is a bug in the console, not something to show raw.
    }
    if (!response.ok) {
      throw new Error((payload && payload.error) || 'The console refused that request.');
    }
    return payload;
  }

  /** Waits for the reader to stop typing, so a search box does not query per keystroke. */
  function debounce(fn, wait) {
    var timer = null;
    return function () {
      var args = arguments;
      window.clearTimeout(timer);
      timer = window.setTimeout(function () { fn.apply(null, args); }, wait);
    };
  }

  /** An element with text set as text. Never innerHTML: this data is whatever was stored. */
  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) {
      node.className = className;
    }
    if (text !== undefined && text !== null) {
      node.textContent = String(text);
    }
    return node;
  }

  function formatDate(iso) {
    if (!iso) {
      return '';
    }
    var date = new Date(iso);
    if (isNaN(date.getTime())) {
      return '';
    }
    return new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium',
      timeStyle: 'short'
    }).format(date);
  }

  /** Replaces a container's children in one go. */
  function replace(container, nodes) {
    container.textContent = '';
    nodes.forEach(function (node) { container.appendChild(node); });
  }

  function showState(container, message, modifier) {
    replace(container, [el('div', 'state' + (modifier ? ' state--' + modifier : ''), message)]);
  }

  /**
   * Watches a sentinel and calls back when it comes into view.
   *
   * This is what "load another window when the reader scrolls" is built from.
   *
   * `scroller` matters more than it looks. An IntersectionObserver watches the viewport by
   * default, so a sentinel inside a panel that scrolls on its own is judged against the
   * wrong box: it reads as permanently visible, fires twice on load and then never again,
   * because intersection has not *changed*. The result is a list that loads two windows and
   * then silently stops responding to the scrollbar the reader is actually dragging.
   *
   * The margin is small on purpose. A generous one fetches further ahead of the reader,
   * which on a tall window means the first paint quietly becomes three windows rather than
   * one — and the point of paging here is that no single request reads the whole table.
   *
   * @param scroller the element the sentinel scrolls inside, or null for the page itself
   * @returns a function reporting whether the sentinel is in view right now, so a caller
   *          can keep filling a container that is still not full
   */
  function onVisible(sentinel, callback, scroller) {
    var visible = false;
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        visible = entry.isIntersecting;
        if (visible) {
          callback();
        }
      });
    }, { root: scroller || null, rootMargin: '120px' });
    observer.observe(sentinel);
    return function () { return visible; };
  }

  /* ---------------------------------------------------------------- chrome */

  async function loadPartial(name, mountId) {
    var mount = document.getElementById(mountId);
    if (!mount) {
      return;
    }
    try {
      var response = await fetch(root + 'assets/partials/' + name + '.html', {
        headers: { 'X-MCAgents-Token': token }
      });
      var markup = await response.text();
      mount.innerHTML = markup
        .split('{{ROOT}}').join(root)
        .split('{{TOKEN}}').join(encodeURIComponent(token));
    } catch (error) {
      // Losing the chrome must not lose the page: the content below still renders.
      mount.textContent = '';
    }
  }

  function wireHeader() {
    var toggle = document.querySelector('.nav__toggle');
    var links = document.querySelector('.nav__links');
    if (toggle && links) {
      toggle.addEventListener('click', function () {
        var open = links.classList.toggle('is-open');
        toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
      });
    }
    var section = window.PAGE_SECTION;
    if (section) {
      document.querySelectorAll('[data-section]').forEach(function (node) {
        if (node.getAttribute('data-section') === section) {
          node.classList.add('is-active');
          node.setAttribute('aria-current', 'page');
        }
      });
    }
  }

  function stampYear() {
    var year = document.querySelector('[data-year]');
    if (year) {
      year.textContent = String(new Date().getFullYear());
    }
  }

  /** Inlined rather than fetched, for the same reason nothing else is fetched. */
  function injectFavicon() {
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">'
      + '<rect width="32" height="32" rx="9" fill="#5b7189"/>'
      + '<circle cx="16" cy="16" r="7" fill="none" stroke="#fff" stroke-width="2.5"/>'
      + '</svg>';
    var icon = document.createElement('link');
    icon.rel = 'icon';
    icon.type = 'image/svg+xml';
    icon.href = 'data:image/svg+xml;base64,' + window.btoa(svg);
    document.head.appendChild(icon);
  }

  async function boot() {
    injectFavicon();
    await Promise.all([
      loadPartial('header', 'site-header'),
      loadPartial('footer', 'site-footer')
    ]);
    wireHeader();
    stampYear();
    document.dispatchEvent(new CustomEvent('mcagents:ready'));
  }

  /**
   * Wires a "download what I do not have" button.
   *
   * The refresh button on the knowledge page and the update button on the chat page are the
   * same operation — the second exists only because the chat page is where a player notices
   * the agent is missing something. One implementation, so they cannot behave differently.
   *
   * Disabled while it runs and while the service is unreachable, which is most of what stops
   * a player producing the flood the service rate-limits.
   */
  function wireSyncButton(button, note, onDone) {
    if (!button) {
      return;
    }
    function say(message, kind) {
      if (note) {
        note.textContent = message || '';
        note.className = 'sync-note' + (kind ? ' is-' + kind : '');
      }
    }

    button.addEventListener('click', async function () {
      button.disabled = true;
      say('Checking…');
      try {
        var result = await api('/service/refresh', { method: 'POST' });
        if (result.downloaded > 0) {
          say('Downloaded ' + result.downloaded
            + (result.downloaded === 1 ? ' pack.' : ' packs.'), 'ok');
        } else if (result.failed > 0) {
          say(result.failed + ' could not be downloaded.', 'error');
        } else {
          say('Already up to date.', 'ok');
        }
        if (onDone) {
          onDone(result);
        }
      } catch (error) {
        say(error.message, 'error');
      } finally {
        button.disabled = false;
      }
    });
  }

  window.MCAgents = {
    token: token,
    root: root,
    link: link,
    api: api,
    debounce: debounce,
    el: el,
    replace: replace,
    showState: showState,
    onVisible: onVisible,
    wireSyncButton: wireSyncButton,
    formatDate: formatDate
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
