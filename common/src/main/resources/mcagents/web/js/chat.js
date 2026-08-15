/*
 * The chat page: the same conversations the game shows, in a window with a scrollbar.
 *
 * Two things make this more than a viewer. It writes — a question asked here is stored in
 * the same database and answered by the same agent with the same key, so it is not a
 * preview of the in-game screen but another way into it. And it watches: the console keeps
 * a counter that moves whenever any conversation changes, from either side, so a message
 * typed in Minecraft appears here without the reader doing anything.
 */
(function () {
  'use strict';

  var PAGE_SIZE = 10;

  /* How often to ask whether anything changed. One integer over loopback; the cost is the
   * request, not the query, and three seconds is under the threshold where a reader
   * notices the lag. */
  var POLL_MS = 3000;

  var listEl;
  var sentinel;
  var sentinelVisible;
  var searchBox;
  var messagesEl;
  var titleEl;
  var promptBox;
  var sendButton;
  var deleteButton;
  var renameButton;
  var countLabel;

  var state = {
    query: '',
    offset: 0,
    total: 0,
    hasMore: true,
    loading: false,
    run: 0,
    activeId: null,
    revision: null,
    sending: false
  };

  /* ------------------------------------------------------------- the list */

  function sessionRow(session) {
    var M = window.MCAgents;
    var node = document.createElement('button');
    node.type = 'button';
    node.className = 'chat-row' + (session.id === state.activeId ? ' is-active' : '');
    node.dataset.sessionId = session.id;

    node.appendChild(M.el('span', 'chat-row__title', session.title));
    node.appendChild(M.el('span', 'chat-row__time', M.formatDate(session.updatedAt)));
    return node;
  }

  async function loadMore() {
    if (state.loading || !state.hasMore) {
      return;
    }
    var M = window.MCAgents;
    var run = state.run;
    state.loading = true;

    var params = new URLSearchParams();
    params.set('offset', String(state.offset));
    params.set('limit', String(PAGE_SIZE));
    if (state.query) {
      params.set('q', state.query);
    }

    try {
      var page = await M.api('/chats?' + params.toString());
      if (run !== state.run) {
        return;
      }
      if (state.offset === 0) {
        listEl.textContent = '';
        listEl.appendChild(sentinel);
      }
      page.items.forEach(function (session) {
        listEl.insertBefore(sessionRow(session), sentinel);
      });

      state.offset = page.nextOffset;
      state.total = page.total;
      state.hasMore = page.hasMore;
      countLabel.textContent = state.total
        ? Math.min(state.offset, state.total) + ' of ' + state.total : '';

      if (page.total === 0) {
        M.showState(listEl, state.query ? 'No chats match that.' : 'No chats yet.');
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
    // The sidebar may still not be full — a tall window shows more than one window's worth
    // of rows, and the observer will not fire again while the sentinel never left view.
    if (run === state.run && state.hasMore && sentinelVisible && sentinelVisible()) {
      await loadMore();
    }
  }

  function resetList() {
    state.run += 1;
    state.offset = 0;
    state.total = 0;
    state.hasMore = true;
    state.loading = false;
    window.MCAgents.showState(listEl, 'Loading…');
    listEl.appendChild(sentinel);
    return loadMore();
  }

  /* --------------------------------------------------------- conversation */

  function messageBubble(message) {
    var M = window.MCAgents;
    var wrapper = M.el('div', 'msg msg--' + message.role);
    wrapper.appendChild(M.el('div', 'msg__who',
      message.role === 'user' ? 'You' : message.role === 'assistant' ? 'Agent' : 'Context'));
    /* textContent: a message is whatever was typed or whatever a model returned. */
    wrapper.appendChild(M.el('div', 'msg__body', message.content));
    wrapper.appendChild(M.el('div', 'msg__time', M.formatDate(message.createdAt)));
    return wrapper;
  }

  async function openSession(id, options) {
    var M = window.MCAgents;
    var settings = options || {};
    state.activeId = id;

    listEl.querySelectorAll('.chat-row').forEach(function (row) {
      row.classList.toggle('is-active', row.dataset.sessionId === id);
    });
    deleteButton.disabled = false;
    renameButton.disabled = false;
    promptBox.disabled = false;
    updateSendState();

    if (!settings.quiet) {
      M.showState(messagesEl, 'Loading…');
    }
    try {
      var payload = await M.api('/chats/' + encodeURIComponent(id) + '/messages');
      if (state.activeId !== id) {
        return;
      }
      if (payload.items.length === 0) {
        M.showState(messagesEl, 'Ask something to start.');
      } else {
        M.replace(messagesEl, payload.items.map(messageBubble));
        messagesEl.scrollTop = messagesEl.scrollHeight;
      }
      var row = listEl.querySelector('[data-session-id="' + CSS.escape(id) + '"]');
      titleEl.textContent = row ? row.querySelector('.chat-row__title').textContent : 'Chat';
    } catch (error) {
      if (state.activeId === id) {
        M.showState(messagesEl, error.message, 'error');
      }
    }
  }

  function updateSendState() {
    sendButton.disabled = state.sending || !state.activeId || promptBox.value.trim().length === 0;
  }

  async function send() {
    if (sendButton.disabled) {
      return;
    }
    var M = window.MCAgents;
    var prompt = promptBox.value.trim();
    var sessionId = state.activeId;

    promptBox.value = '';
    state.sending = true;
    updateSendState();

    /* Shown immediately rather than after the round trip: the reader typed it, so waiting
     * to display it makes the page look like it lost the message. */
    if (messagesEl.querySelector('.state')) {
      messagesEl.textContent = '';
    }
    messagesEl.appendChild(messageBubble({
      role: 'user', content: prompt, createdAt: new Date().toISOString()
    }));
    var pending = M.el('div', 'msg msg--assistant is-pending');
    pending.appendChild(M.el('div', 'msg__who', 'Agent'));
    var waiting = M.el('div', 'msg__body');
    waiting.appendChild(M.el('span', 'spinner'));
    waiting.appendChild(M.el('span', '', 'Thinking…'));
    pending.appendChild(waiting);
    messagesEl.appendChild(pending);
    messagesEl.scrollTop = messagesEl.scrollHeight;

    try {
      var result = await M.api('/chats/' + encodeURIComponent(sessionId) + '/messages', {
        method: 'POST',
        body: { prompt: prompt }
      });
      pending.remove();
      if (state.activeId === sessionId) {
        messagesEl.appendChild(messageBubble(result.answer));
        messagesEl.scrollTop = messagesEl.scrollHeight;
      }
    } catch (error) {
      pending.remove();
      var failure = M.el('div', 'msg msg--error');
      failure.appendChild(M.el('div', 'msg__who', 'Not answered'));
      failure.appendChild(M.el('div', 'msg__body', error.message));
      messagesEl.appendChild(failure);
      messagesEl.scrollTop = messagesEl.scrollHeight;
    } finally {
      state.sending = false;
      updateSendState();
      /* The question was stored whether or not it was answered, so the list re-sorts. */
      await syncRevision();
      await resetList();
      if (state.activeId === sessionId) {
        listEl.querySelectorAll('.chat-row').forEach(function (row) {
          row.classList.toggle('is-active', row.dataset.sessionId === sessionId);
        });
      }
    }
  }

  /* ---------------------------------------------------------- the syncing */

  /** Records where the counter is now, so the poll does not react to our own writes. */
  async function syncRevision() {
    try {
      var payload = await window.MCAgents.api('/revision');
      state.revision = payload.chat;
    } catch (ignored) {
      /* The console is gone or busy; the next tick tries again. */
    }
  }

  /**
   * Notices what the game changed.
   *
   * The counter moves on any conversation write from either side, so a message typed in
   * Minecraft lands here within a poll — which is the whole reason the counter lives in the
   * repository rather than in the console.
   */
  async function poll() {
    if (state.sending) {
      return;
    }
    try {
      var payload = await window.MCAgents.api('/revision');
      if (state.revision !== null && payload.chat !== state.revision) {
        state.revision = payload.chat;
        await resetList();
        if (state.activeId) {
          await openSession(state.activeId, { quiet: true });
        }
      } else {
        state.revision = payload.chat;
      }
    } catch (ignored) {
      /* A failed poll is not worth showing; the page keeps working on what it has. */
    }
  }

  /* --------------------------------------------------------------- wiring */

  async function newChat() {
    var M = window.MCAgents;
    try {
      var session = await M.api('/chats', { method: 'POST', body: { title: '' } });
      await syncRevision();
      state.query = '';
      searchBox.value = '';
      await resetList();
      await openSession(session.id);
      promptBox.focus();
    } catch (error) {
      M.showState(messagesEl, error.message, 'error');
    }
  }

  async function renameChat() {
    if (!state.activeId) {
      return;
    }
    var current = titleEl.textContent;
    var next = window.prompt('Rename this chat', current);
    if (next === null) {
      return;
    }
    await window.MCAgents.api('/chats/' + encodeURIComponent(state.activeId), {
      method: 'PUT',
      body: { title: next }
    });
    await syncRevision();
    await resetList();
    titleEl.textContent = next.trim() || 'New Chat';
  }

  async function deleteChat() {
    if (!state.activeId) {
      return;
    }
    if (!window.confirm('Delete this chat and every message in it? This cannot be undone.')) {
      return;
    }
    await window.MCAgents.api('/chats/' + encodeURIComponent(state.activeId), { method: 'DELETE' });
    state.activeId = null;
    titleEl.textContent = 'No chat selected';
    deleteButton.disabled = true;
    renameButton.disabled = true;
    promptBox.disabled = true;
    updateSendState();
    window.MCAgents.showState(messagesEl, 'Pick a chat on the left, or start a new one.');
    await syncRevision();
    await resetList();
  }

  document.addEventListener('mcagents:ready', async function () {
    var M = window.MCAgents;

    listEl = document.getElementById('chat-list');
    sentinel = document.getElementById('scroll-sentinel');
    searchBox = document.getElementById('chat-search');
    messagesEl = document.getElementById('messages');
    titleEl = document.getElementById('chat-title');
    promptBox = document.getElementById('prompt');
    sendButton = document.getElementById('send');
    deleteButton = document.getElementById('delete-chat');
    renameButton = document.getElementById('rename-chat');
    countLabel = document.getElementById('chat-count');

    searchBox.addEventListener('input', M.debounce(function () {
      state.query = searchBox.value.trim();
      resetList();
    }, 180));

    listEl.addEventListener('click', function (event) {
      var row = event.target.closest('.chat-row');
      if (row) {
        openSession(row.dataset.sessionId);
      }
    });

    promptBox.addEventListener('input', updateSendState);
    promptBox.addEventListener('keydown', function (event) {
      /* Enter sends, Shift+Enter breaks the line — the same bargain the game screen makes. */
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        send();
      }
    });
    sendButton.addEventListener('click', send);
    document.getElementById('new-chat').addEventListener('click', newChat);
    renameButton.addEventListener('click', renameChat);
    deleteButton.addEventListener('click', deleteChat);

    // Watched against the sidebar's own scroller, not the viewport: the list scrolls
    // inside that panel, so the page's scrollbar says nothing about reaching its end.
    sentinelVisible = M.onVisible(sentinel, loadMore, listEl);
    await syncRevision();
    await resetList();
    window.setInterval(poll, POLL_MS);
  });
})();
