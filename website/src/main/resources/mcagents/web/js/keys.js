/*
 * The API key page: list, add, remove.
 *
 * What this page never has is a key. The console sends a mask to recognise one by and an
 * opaque handle to act on it with, and resolves that handle back to a value inside the mod.
 * So there is nothing here to copy, nothing to leak into a screenshot, and nothing a page
 * that went wrong could hand to anybody.
 *
 * It does have the platform a key opens and the address that platform is called at. Neither is
 * a secret — one is a provider's name and the other is a public API endpoint — and without
 * them two masked keys are indistinguishable and a direct route is invisible.
 */
(function () {
  'use strict';

  var PAGE_SIZE = 10;

  var listEl;
  var scroller;
  var sentinel;
  var sentinelVisible;
  var countLabel;
  var valueBox;
  var platformBox;
  var platformOptions;
  var addButton;
  var noteEl;
  var routeList;
  var routeNote;
  var encryptionNote;
  var encryptionText;

  var state = {
    offset: 0,
    total: 0,
    hasMore: true,
    loading: false,
    run: 0
  };

  function keyRow(key) {
    var M = window.MCAgents;
    var row = M.el('div', 'key-row');

    /* masked, never the value — the console does not send one and this could not show it. */
    row.appendChild(M.el('span', 'key-row__value', key.masked));
    row.appendChild(M.el('span', 'badge', key.platform || 'openrouter'));
    row.appendChild(M.el('span',
      'badge ' + (key.active ? 'badge--ok' : 'badge--warn'),
      key.active ? 'Active' : 'Rejected'));
    row.appendChild(M.el('span', 'key-row__when', M.formatDate(key.createdAt)));

    var remove = M.el('button', 'btn btn--small btn--danger', 'Remove');
    remove.type = 'button';
    remove.dataset.handle = key.handle;
    row.appendChild(remove);

    return row;
  }

  function scrollRoot() {
    return scroller && getComputedStyle(scroller).overflowY === 'auto' ? scroller : null;
  }

  async function loadMore() {
    if (state.loading || !state.hasMore) {
      return;
    }
    var M = window.MCAgents;
    var run = state.run;
    state.loading = true;

    try {
      var page = await M.api('/keys?offset=' + state.offset + '&limit=' + PAGE_SIZE);
      if (run !== state.run) {
        return;
      }
      if (state.offset === 0) {
        listEl.textContent = '';
      }
      page.items.forEach(function (key) { listEl.appendChild(keyRow(key)); });

      state.offset = page.nextOffset;
      state.total = page.total;
      state.hasMore = page.hasMore;
      countLabel.textContent = state.total
        ? Math.min(state.offset, state.total) + ' of ' + state.total
          + (state.total === 1 ? ' key' : ' keys')
        : '';

      if (page.total === 0) {
        M.showState(listEl, 'No keys yet. Add one to let the agent answer.');
      }
    } catch (error) {
      if (run === state.run && state.offset === 0) {
        M.showState(listEl, error.message, 'error');
      }
    } finally {
      if (run === state.run) {
        state.loading = false;
      }
    }
    if (run === state.run && state.hasMore && sentinelVisible && sentinelVisible()) {
      await loadMore();
    }
  }

  function reset() {
    state.run += 1;
    state.offset = 0;
    state.total = 0;
    state.hasMore = true;
    state.loading = false;
    window.MCAgents.showState(listEl, 'Loading…');
    return loadMore();
  }

  function note(message, kind) {
    noteEl.textContent = message;
    noteEl.className = 'muted key-note' + (kind ? ' is-' + kind : '');
  }

  async function addKey(event) {
    event.preventDefault();
    var M = window.MCAgents;
    var value = valueBox.value.trim();
    if (!value) {
      return;
    }

    addButton.disabled = true;
    try {
      await M.api('/keys', {
        method: 'POST',
        body: { value: value, platform: platformBox.value.trim() }
      });
      /* Cleared the moment it is stored: a key left sitting in a visible field is a key
       * that survives into a screenshot or a stream. */
      valueBox.value = '';
      note('Key added.', 'ok');
      /* The new key may have made its platform reachable directly, so both halves refresh. */
      await Promise.all([reset(), loadRoutes()]);
    } catch (error) {
      note(error.message, 'error');
    } finally {
      addButton.disabled = valueBox.value.trim().length === 0;
    }
  }

  async function removeKey(handle, row) {
    var confirmed = await window.MCAgents.confirm({
      title: 'Remove this key?',
      message: 'The mod stops using it immediately. Nothing here can show you the key '
        + 'again, so make sure you have it somewhere else if you still want it.',
      confirmLabel: 'Remove key',
      danger: true
    });
    if (!confirmed) {
      return;
    }
    try {
      await window.MCAgents.api('/keys/' + encodeURIComponent(handle), { method: 'DELETE' });
      note('Key removed.', 'ok');
      await reset();
    } catch (error) {
      note(error.message, 'error');
    }
  }

  /* One platform: where it is called, and whether it is called there. */
  function routeRow(item) {
    var M = window.MCAgents;
    var row = M.el('div', 'route-row');

    row.appendChild(M.el('span', 'route-row__name', item.platform));

    var url = M.el('input', 'field route-row__url');
    url.type = 'url';
    url.spellcheck = false;
    url.value = item.configuredUrl || '';
    /* The known address when there is one, and a word when there is not — never a literal
     * URL, which the page guard reads as something fetched off the machine. */
    url.placeholder = item.knownUrl || 'Not set';
    url.dataset.platform = item.platform;
    url.setAttribute('aria-label', 'Base URL for ' + item.platform);
    row.appendChild(url);

    var save = M.el('button', 'btn btn--small', 'Save');
    save.type = 'button';
    save.dataset.savePlatform = item.platform;
    row.appendChild(save);

    /* Says which of the two routes this platform is actually on, and why when it is not the
     * direct one — a URL with no key is as useless as a key with no URL. */
    var state = item.direct
      ? M.el('span', 'badge badge--ok', 'Direct')
      : M.el('span', 'badge', item.hasKey ? 'No address' : 'Via OpenRouter');
    row.appendChild(state);

    return row;
  }

  async function loadRoutes() {
    var M = window.MCAgents;
    try {
      var body = await M.api('/routes');

      routeList.textContent = '';
      if (!body.items.length) {
        M.showState(routeList, 'Nothing to route yet — every question goes through OpenRouter.');
      } else {
        body.items.forEach(function (item) { routeList.appendChild(routeRow(item)); });
      }

      platformOptions.textContent = '';
      (body.suggestedPlatforms || []).forEach(function (platform) {
        var option = document.createElement('option');
        option.value = platform;
        platformOptions.appendChild(option);
      });

      var encryption = body.encryption || {};
      encryptionText.textContent = encryption.enabled
        ? 'Stored keys are encrypted on this machine. Keep ' + encryption.variable
          + ' set to the same value, or the mod cannot read them back.'
        : 'Stored keys are kept as you pasted them. Set ' + encryption.variable
          + ' before starting the game to have them encrypted in the database.';
      encryptionNote.hidden = false;
    } catch (error) {
      M.showState(routeList, error.message, 'error');
    }
  }

  async function saveRoute(platform, box) {
    var M = window.MCAgents;
    try {
      await M.api('/routes', {
        method: 'PUT',
        body: { platform: platform, baseUrl: box.value.trim() }
      });
      routeNote.textContent = 'Saved. ' + platform + ' will be used on the next question.';
      routeNote.className = 'muted key-note is-ok';
      await loadRoutes();
    } catch (error) {
      routeNote.textContent = error.message;
      routeNote.className = 'muted key-note is-error';
    }
  }

  document.addEventListener('mcagents:ready', async function () {
    var M = window.MCAgents;

    listEl = document.getElementById('key-list');
    scroller = document.getElementById('key-scroller');
    sentinel = document.getElementById('scroll-sentinel');
    countLabel = document.getElementById('key-count');
    valueBox = document.getElementById('key-value');
    platformBox = document.getElementById('key-platform');
    platformOptions = document.getElementById('platform-options');
    addButton = document.getElementById('add-key');
    noteEl = document.getElementById('add-note');
    routeList = document.getElementById('route-list');
    routeNote = document.getElementById('route-note');
    encryptionNote = document.getElementById('encryption-note');
    encryptionText = document.getElementById('encryption-text');

    routeList.addEventListener('click', function (event) {
      var button = event.target.closest('button[data-save-platform]');
      if (button) {
        saveRoute(button.dataset.savePlatform,
          button.closest('.route-row').querySelector('.route-row__url'));
      }
    });

    valueBox.addEventListener('input', function () {
      addButton.disabled = valueBox.value.trim().length === 0;
    });
    document.getElementById('add-form').addEventListener('submit', addKey);

    listEl.addEventListener('click', function (event) {
      var button = event.target.closest('button[data-handle]');
      if (button) {
        removeKey(button.dataset.handle, button.closest('.key-row'));
      }
    });

    sentinelVisible = M.onVisible(sentinel, loadMore, scrollRoot());
    await Promise.all([reset(), loadRoutes()]);
  });
})();
