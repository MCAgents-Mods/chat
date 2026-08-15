/*
 * The API key page: list, add, remove.
 *
 * What this page never has is a key. The console sends a mask to recognise one by and an
 * opaque handle to act on it with, and resolves that handle back to a value inside the mod.
 * So there is nothing here to copy, nothing to leak into a screenshot, and nothing a page
 * that went wrong could hand to anybody.
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
  var addButton;
  var noteEl;

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
      await M.api('/keys', { method: 'POST', body: { value: value } });
      /* Cleared the moment it is stored: a key left sitting in a visible field is a key
       * that survives into a screenshot or a stream. */
      valueBox.value = '';
      note('Key added.', 'ok');
      await reset();
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

  document.addEventListener('mcagents:ready', async function () {
    var M = window.MCAgents;

    listEl = document.getElementById('key-list');
    scroller = document.getElementById('key-scroller');
    sentinel = document.getElementById('scroll-sentinel');
    countLabel = document.getElementById('key-count');
    valueBox = document.getElementById('key-value');
    addButton = document.getElementById('add-key');
    noteEl = document.getElementById('add-note');

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
    await reset();
  });
})();
