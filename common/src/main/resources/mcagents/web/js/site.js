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
   * Paints a state button: the words, the colour, and what assistive technology is told.
   *
   * Shared rather than inlined so the colours keep meaning one thing across the console.
   * The embedding switch is the only such button today and lives on /model/ alone — it was
   * on the chat header too, which gave one setting two homes that could disagree about what
   * it currently was.
   */
  function paintToggle(button, enabled, labels) {
    var words = labels || { on: 'Embedding on', off: 'Embedding off' };
    button.textContent = enabled ? words.on : words.off;
    button.classList.toggle('is-on', enabled);
    button.classList.toggle('is-off', !enabled);
    button.setAttribute('aria-pressed', enabled ? 'true' : 'false');
    button.title = enabled
      ? 'Questions are matched against your Knowledge Packs, and the closest are sent with them.'
      : 'Questions are sent on their own. Knowledge Packs are not used, and no embedding is charged for.';
  }

  /**
   * Wires the "update the packs I already have" button.
   *
   * It asks first. The operation spends somebody's bandwidth downloading pack bodies while
   * they are in a game, and every pack it replaces loses its embedding — so the true cost of
   * pressing it includes the provider calls needed to embed the result. A button that
   * expensive should not be one an accidental click can fire.
   *
   * It updates and never acquires: a pack this machine does not have is not fetched here.
   * Taking a new one is done from that pack's own page, where the reader can see what they
   * are taking.
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
      var confirmed = await confirmDialog({
        title: 'Update your Knowledge Packs?',
        message: 'Packs already on this machine are brought up to their published versions. '
          + 'Nothing new is downloaded. An updated pack loses its embedding, because the new '
          + 'version is new text — you will need to embed it again afterwards.',
        confirmLabel: 'Update packs'
      });
      if (!confirmed) {
        return;
      }
      button.disabled = true;
      say('Checking…');
      try {
        var result = await api('/service/update', { method: 'POST' });
        if (result.downloaded > 0) {
          say('Updated ' + result.downloaded
            + (result.downloaded === 1 ? ' pack.' : ' packs.'), 'ok');
        } else if (result.failed > 0) {
          say(result.failed + ' could not be updated.', 'error');
        } else {
          say('Every pack you have is already current.', 'ok');
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

  /* ---------------------------------------------------------------- dialog */

  /*
   * The console's own confirm and prompt.
   *
   * window.confirm and window.prompt are not usable here, for reasons that outlast taste:
   * the browser owns the wording and the button order, so a page cannot label the button
   * with what it is about to do; and every major browser lets a reader tick "prevent this
   * page from creating more dialogs", after which confirm() returns false and prompt()
   * returns null forever. A destructive action guarded by a suppressed confirm is an action
   * that silently stops working — or worse, one whose guard is gone.
   *
   * One element, built once and reused. Two dialogs cannot be open at a time, so there is no
   * reason for two to exist.
   */
  var dialogParts = null;

  function buildDialog() {
    if (dialogParts) {
      return dialogParts;
    }
    var scrim = el('div', 'scrim');
    scrim.hidden = true;

    var box = el('div', 'dialog');
    box.setAttribute('role', 'dialog');
    box.setAttribute('aria-modal', 'true');

    var title = el('h2', 'dialog__title');
    title.id = 'dialog-title';
    box.setAttribute('aria-labelledby', title.id);

    var body = el('p', 'dialog__body');
    var fieldWrap = el('div', 'dialog__field');
    var field = el('input', 'field');
    field.type = 'text';
    field.autocomplete = 'off';
    fieldWrap.appendChild(field);

    var actions = el('div', 'dialog__actions');
    var cancel = el('button', 'btn', 'Cancel');
    cancel.type = 'button';
    var confirm = el('button', 'btn btn--primary', 'Confirm');
    confirm.type = 'button';
    actions.appendChild(cancel);
    actions.appendChild(confirm);

    box.appendChild(title);
    box.appendChild(body);
    box.appendChild(fieldWrap);
    box.appendChild(actions);
    scrim.appendChild(box);
    document.body.appendChild(scrim);

    dialogParts = {
      scrim: scrim, box: box, title: title, body: body,
      fieldWrap: fieldWrap, field: field, cancel: cancel, confirm: confirm
    };
    return dialogParts;
  }

  /**
   * Opens the dialog and resolves with what the reader chose.
   *
   * Resolves rather than rejects on cancel: cancelling is an ordinary answer, and a caller
   * that had to catch it would treat "no" as a failure.
   *
   * @param options title, message, confirmLabel, danger, and prompt/value for a text answer
   * @returns false or null when cancelled; true, or the typed string, when confirmed
   */
  function openDialog(options) {
    var parts = buildDialog();
    var settings = options || {};
    var wantsText = Object.prototype.hasOwnProperty.call(settings, 'value');
    var returning = document.activeElement;

    parts.title.textContent = settings.title || 'Are you sure?';
    parts.body.textContent = settings.message || '';
    parts.body.hidden = !settings.message;
    parts.fieldWrap.hidden = !wantsText;
    parts.field.value = wantsText ? (settings.value || '') : '';
    parts.confirm.textContent = settings.confirmLabel || 'Confirm';
    parts.confirm.className = 'btn ' + (settings.danger ? 'btn--danger' : 'btn--primary');
    parts.cancel.textContent = settings.cancelLabel || 'Cancel';
    parts.scrim.hidden = false;

    /* The text box if there is one, otherwise Cancel — never the confirming button. The
     * dialog opens under wherever the reader was already clicking, and a stray second click
     * must not be able to land on the destructive choice. */
    (wantsText ? parts.field : parts.cancel).focus();
    if (wantsText) {
      parts.field.select();
    }

    return new Promise(function (resolve) {
      function close(answer) {
        parts.scrim.hidden = true;
        parts.cancel.removeEventListener('click', onCancel);
        parts.confirm.removeEventListener('click', onConfirm);
        parts.scrim.removeEventListener('mousedown', onScrim);
        document.removeEventListener('keydown', onKey, true);
        if (returning && returning.focus) {
          returning.focus();
        }
        resolve(answer);
      }
      function onCancel() { close(wantsText ? null : false); }
      function onConfirm() { close(wantsText ? parts.field.value : true); }
      function onScrim(event) {
        /* Only the backdrop itself. A drag that started inside the box and ended out here
         * is a text selection, not a dismissal. */
        if (event.target === parts.scrim) {
          onCancel();
        }
      }
      function onKey(event) {
        if (event.key === 'Escape') {
          event.preventDefault();
          onCancel();
          return;
        }
        if (event.key === 'Enter' && (wantsText || document.activeElement !== parts.cancel)) {
          event.preventDefault();
          onConfirm();
          return;
        }
        if (event.key !== 'Tab') {
          return;
        }
        /* Focus stays inside while this is open, which is what "modal" means to anyone
         * navigating by keyboard. */
        var focusable = wantsText
          ? [parts.field, parts.cancel, parts.confirm]
          : [parts.cancel, parts.confirm];
        var index = focusable.indexOf(document.activeElement);
        var next = event.shiftKey ? index - 1 : index + 1;
        if (index === -1 || next < 0 || next >= focusable.length) {
          event.preventDefault();
          focusable[event.shiftKey ? focusable.length - 1 : 0].focus();
        }
      }

      parts.cancel.addEventListener('click', onCancel);
      parts.confirm.addEventListener('click', onConfirm);
      parts.scrim.addEventListener('mousedown', onScrim);
      document.addEventListener('keydown', onKey, true);
    });
  }

  /** Asks a yes/no question. Resolves true only if the reader confirmed. */
  function confirmDialog(options) {
    var settings = options || {};
    delete settings.value;
    return openDialog(settings);
  }

  /** Asks for a line of text. Resolves null if the reader cancelled. */
  function promptDialog(options) {
    var settings = options || {};
    settings.value = settings.value || '';
    return openDialog(settings);
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
    confirm: confirmDialog,
    prompt: promptDialog,
    onVisible: onVisible,
    wireSyncButton: wireSyncButton,
    paintToggle: paintToggle,
    formatDate: formatDate
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
