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
  var providerBox;
  var providerOtherBox;
  var providerRoute;
  var providerNote;

  /* The "Other…" row, and the same bargain the key page makes: blank is never a platform, so
   * it can stand for "I will name one" without colliding with an answer. It also stands for
   * the state a stored model id can genuinely be in — one with no platform half at all, which
   * is a model going through the gateway with nobody named. */
  var PROVIDER_OTHER = '';

  /* Held as a value, because an empty select reads as '' and '' is the "Other…" row. */
  var providerChoice = PROVIDER_OTHER;

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
    var next = await window.MCAgents.prompt({
      title: 'Rename this chat',
      message: 'The name is only for finding it again; it changes nothing about the '
        + 'conversation.',
      value: current,
      confirmLabel: 'Rename'
    });
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
    var confirmed = await window.MCAgents.confirm({
      title: 'Delete this chat?',
      message: 'Every message in it goes too, on this machine and in the game. There is no '
        + 'undo.',
      confirmLabel: 'Delete chat',
      danger: true
    });
    if (!confirmed) {
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

  /* ---------------------------------------------------------- who answers */

  /*
   * The provider selector.
   *
   * A model id names both halves of the answer — who serves it and which model it is — so
   * choosing a provider here cannot leave the model alone. The rule for what the id becomes
   * is ModelCatalog's, applied by the console: this page sends the platform and is told the
   * id that resulted, which is why the note under the box says what was stored rather than
   * what was asked for.
   *
   * The gateway is not on the list. OpenRouter is a route, not a model's platform, and
   * openrouter/gpt-4o-mini is a model nobody serves — so the list offers platforms that
   * serve models, and the badge beside it says whether this one is reached directly or
   * through the gateway.
   */
  function fillProviders(platforms, gateway) {
    var offered = (platforms || []).filter(function (platform) {
      return platform !== gateway;
    });
    providerBox.textContent = '';

    offered.forEach(function (platform) {
      var option = document.createElement('option');
      option.value = platform;
      option.textContent = platform;
      providerBox.appendChild(option);
    });

    var other = document.createElement('option');
    other.value = PROVIDER_OTHER;
    other.textContent = 'Other…';
    providerBox.appendChild(other);

    if (providerChoice !== PROVIDER_OTHER && offered.indexOf(providerChoice) < 0) {
      var option = document.createElement('option');
      option.value = providerChoice;
      option.textContent = providerChoice;
      providerBox.insertBefore(option, providerBox.firstChild);
    }
    providerBox.value = providerChoice;
    paintProviderOther();
  }

  function paintProviderOther() {
    var typing = providerChoice === PROVIDER_OTHER;
    providerOtherBox.hidden = !typing;
    if (!typing) {
      providerOtherBox.value = '';
    }
  }

  /* Direct or through the gateway, for the platform now chosen. Both are working states, so
   * neither is an error — the badge says which one, the way the key page does. */
  function paintRoute(routes, platform) {
    var direct = false;
    (routes.items || []).forEach(function (item) {
      if (item.platform === platform && item.direct) {
        direct = true;
      }
    });
    providerRoute.hidden = !platform;
    providerRoute.textContent = direct ? 'Direct' : 'Via ' + routes.gateway;
    providerRoute.className = 'badge' + (direct ? ' badge--ok' : '');
  }

  function sayProvider(message, kind) {
    providerNote.textContent = message || '';
    providerNote.className = 'muted composer-meta__note' + (kind ? ' is-' + kind : '');
  }

  async function loadProviders() {
    var M = window.MCAgents;
    try {
      /* Two questions with one answer between them: which model is stored, and how its
       * platform is reached. Asked together so the badge cannot describe a platform the
       * selector is no longer showing. */
      var answers = await Promise.all([M.api('/models'), M.api('/routes')]);
      var models = answers[0];
      var routes = answers[1];

      providerChoice = models.chatPlatform || PROVIDER_OTHER;
      fillProviders(routes.suggestedPlatforms, routes.gateway);
      paintRoute(routes, models.chatPlatform);
      sayProvider(models.chatPlatform
        ? models.chatModel
        : models.chatModel + ' names no provider — pick one to route it.');
    } catch (error) {
      sayProvider(error.message, 'error');
    }
  }

  async function saveProvider(platform) {
    var M = window.MCAgents;
    if (!platform) {
      sayProvider('Name the provider that should answer, or pick one from the list.', 'error');
      providerOtherBox.focus();
      return;
    }

    providerBox.disabled = true;
    try {
      /* The platform, not an id: the console decides what the model becomes, so this page
       * and the game screen cannot disagree about the rule. */
      var stored = await M.api('/models', {
        method: 'PUT',
        body: { chatPlatform: platform }
      });
      providerChoice = stored.chatPlatform || PROVIDER_OTHER;
      /* The badge is repainted before the note is written, not after.
       *
       * Re-read rather than adjusted, because a platform that just gained its first key is
       * reached differently than it was a moment ago — and written first, because in the
       * other order a browser sees the note announce the new provider while the badge beside
       * it still describes the one just left. The two are one sentence; they land together. */
      paintRoute(await M.api('/routes'), stored.chatPlatform);
      sayProvider(stored.chatModel + ' answers from your next question on.', 'ok');
    } catch (error) {
      sayProvider(error.message, 'error');
    } finally {
      providerBox.disabled = false;
    }
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
    providerBox = document.getElementById('chat-provider');
    providerOtherBox = document.getElementById('chat-provider-other');
    providerRoute = document.getElementById('chat-route');
    providerNote = document.getElementById('chat-provider-note');

    providerBox.addEventListener('change', function () {
      providerChoice = providerBox.value;
      paintProviderOther();
      if (providerChoice === PROVIDER_OTHER) {
        providerOtherBox.focus();
        sayProvider('Name the provider that should answer.');
        return;
      }
      saveProvider(providerChoice);
    });
    /* change, not input: it fires on Enter and on leaving the box, which is when a name is
     * finished being typed rather than while it is still half-written. */
    providerOtherBox.addEventListener('change', function () {
      saveProvider(providerOtherBox.value.trim());
    });

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

    var noteEl = document.getElementById('sync-note');

    /* Embedding is a local job: the player's model provider is asked, and nothing else. */
    var embedButton = document.getElementById('embed-knowledge');

    /* What is still true about the library, on a line of its own.
     *
     * It has its own element rather than sharing the buttons' note. That one reports what a
     * button just did; this reports what is still true, so a result is never wiped a moment
     * later by the standing state it had just changed. */
    var notices = { needingEmbedding: 0 };
    var noticeEl = document.getElementById('knowledge-notice');

    function showNotices() {
      noticeEl.textContent = notices.needingEmbedding > 0
        ? notices.needingEmbedding
          + (notices.needingEmbedding === 1 ? ' pack needs embedding.' : ' packs need embedding.')
        : '';
    }

    async function showEmbeddingStatus() {
      try {
        var status = await M.api('/embeddings');
        notices.needingEmbedding = status.needing;
        showNotices();
        return status;
      } catch (ignored) {
        return null;
      }
    }

    embedButton.addEventListener('click', async function () {
      /* Asks first, because this one spends money. Embedding is a request to the player's
       * model provider per pack, so a library of forty packs is forty billable calls from
       * one click. */
      var status = await showEmbeddingStatus();
      var waiting = status ? status.needing : 0;
      var confirmed = await M.confirm({
        title: waiting === 1 ? 'Embed 1 pack?' : 'Embed ' + waiting + ' packs?',
        message: waiting === 0
          ? 'Nothing needs embedding right now. Running anyway costs nothing and will '
            + 'confirm that.'
          : 'Each one is a separate request to your model provider, charged to your API key. '
            + 'They run one at a time and can take a while.',
        confirmLabel: 'Embed now'
      });
      if (!confirmed) {
        return;
      }
      embedButton.disabled = true;
      noteEl.textContent = 'Embedding…';
      noteEl.className = 'sync-note';
      try {
        var result = await M.api('/embeddings', { method: 'POST' });
        if (result.embedded === 0 && result.failed === 0) {
          noteEl.textContent = 'Every pack is already embedded.';
          noteEl.className = 'sync-note is-ok';
        } else if (result.failed > 0) {
          noteEl.textContent = 'Embedded ' + result.embedded + ', ' + result.failed
            + ' failed. Check your API key.';
          noteEl.className = 'sync-note is-error';
        } else {
          noteEl.textContent = 'Embedded ' + result.embedded
            + (result.embedded === 1 ? ' pack.' : ' packs.');
          noteEl.className = 'sync-note is-ok';
        }
      } catch (error) {
        noteEl.textContent = error.message;
        noteEl.className = 'sync-note is-error';
      } finally {
        embedButton.disabled = false;
      }
    });

    /* The persona, edited in the browser.
     *
     * It writes the same file the game reads, through the same store, so a save here is in
     * force by the next question asked anywhere — no reload, no restart, and nothing to
     * press in the game afterwards.
     *
     * It is fetched when the button is pressed rather than held on the page: the file can
     * be edited in game or in a text editor while this tab sits open, and a modal opened on
     * a copy from ten minutes ago would save that copy back over the newer one. */
    var personaButton = document.getElementById('edit-persona');

    function sayPersona(message, kind) {
      noteEl.textContent = message || '';
      noteEl.className = 'sync-note' + (kind ? ' is-' + kind : '');
    }

    personaButton.addEventListener('click', async function () {
      personaButton.disabled = true;
      sayPersona('Reading AGENTS.md…');
      var current;
      try {
        current = (await M.api('/agent/persona')).content;
      } catch (error) {
        sayPersona(error.message, 'error');
        return;
      } finally {
        personaButton.disabled = false;
      }

      var next = await M.prompt({
        title: 'AGENTS.md',
        message: 'The instructions every conversation starts from. They are sent at the top '
          + 'of every question, in the game and here. Saving takes effect on the next one.',
        value: current,
        multiline: true,
        wide: true,
        confirmLabel: 'Save'
      });
      if (next === null) {
        /* Cancelled. The file is untouched, and saying so would be noise. */
        sayPersona('');
        return;
      }

      sayPersona('Saving…');
      personaButton.disabled = true;
      try {
        await M.api('/agent/persona', { method: 'PUT', body: { content: next } });
        sayPersona('AGENTS.md saved. Your next question uses it.', 'ok');
      } catch (error) {
        sayPersona(error.message, 'error');
      } finally {
        personaButton.disabled = false;
      }
    });

    /* Counting is free — it reads the local table and one setting — so the page can say so
     * on load rather than waiting for the player to wonder why the agent knows nothing. */
    await showEmbeddingStatus();

    await loadProviders();

    await syncRevision();
    await resetList();
    window.setInterval(poll, POLL_MS);
  });
})();
