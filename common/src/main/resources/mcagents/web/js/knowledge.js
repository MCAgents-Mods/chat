/*
 * The knowledge list: twenty at a time, a search box that filters as it is typed, and a
 * category filter.
 *
 * Nothing here loads the whole library. A player may have hundreds of packs, each carrying
 * an embedding of thousands of numbers — so the list asks for summaries, a window at a
 * time, and asks for the next window only when the reader scrolls to the end of this one.
 */
(function () {
  'use strict';

  var PAGE_SIZE = 20;

  /* Refresh on this page re-reads what is here and what is published. It downloads nothing —
   * see the button's own comment. */

  var list;
  var scroller;
  var sentinel;
  var sentinelVisible;
  var countLabel;
  var searchBox;
  var chips;

  /* One object so that a stale response can be recognised and dropped. */
  var state = {
    query: '',
    categoryId: '',
    availability: 'any',
    offset: 0,
    total: 0,
    hasMore: true,
    loading: false,
    /* Bumped whenever the filter changes; a response from an older run is discarded. */
    run: 0,
    /* id -> published version, for the stored packs the service has moved past. Filled by
     * the catalog read; empty until then, and empty whenever the service cannot be asked,
     * so a pack is never marked stale on a guess. */
    behind: {}
  };

  function card(item) {
    var M = window.MCAgents;
    var node = document.createElement('a');
    node.className = 'card';
    node.href = M.link('knowledge/' + encodeURIComponent(item.id));

    node.appendChild(M.el('div', 'card__title', item.title || item.id));
    node.appendChild(M.el('p', 'card__desc', item.id));

    var more = M.el('div', 'card__more');
    more.appendChild(M.el('span', 'badge badge--accent', 'v' + item.version));
    more.appendChild(M.el('span', 'badge' + (item.premium ? ' badge--sponsor' : ''),
      item.premium ? 'Premium' : 'Free'));
    if (item.hasEmbedding) {
      more.appendChild(M.el('span', 'badge badge--ok', 'Embedded'));
    } else {
      more.appendChild(M.el('span', 'badge badge--warn', 'No embedding'));
    }
    /* Only ever from a version the service actually reported. The map is empty until the
     * catalog has been read and empty again if it could not be, so an unreachable service
     * shows no badge rather than a wrong one. */
    if (state.behind[item.id]) {
      more.appendChild(M.el('span', 'badge badge--sponsor',
        'v' + state.behind[item.id] + ' published'));
    }
    more.appendChild(M.el('span', '', M.formatDate(item.updatedAt)));
    node.appendChild(more);

    return node;
  }

  /**
   * A pack the player does not have yet.
   *
   * Same card, one badge different, and it links to the same detail page — which is where
   * the download button lives, because that is the only page about one pack.
   */
  function availableCard(item) {
    var M = window.MCAgents;
    var node = document.createElement('a');
    node.className = 'card';
    node.href = M.link('knowledge/' + encodeURIComponent(item.id));

    node.appendChild(M.el('div', 'card__title', item.title || item.id));
    node.appendChild(M.el('p', 'card__desc', item.id));

    var more = M.el('div', 'card__more');
    more.appendChild(M.el('span', 'badge badge--accent', 'v' + item.version));
    more.appendChild(M.el('span', 'badge' + (item.premium ? ' badge--sponsor' : ''),
      item.premium ? 'Premium' : 'Free'));
    more.appendChild(M.el('span', 'badge badge--warn', 'Not downloaded'));
    node.appendChild(more);

    return node;
  }

  /**
   * Asks the service what it publishes that is not here.
   *
   * The section stays on the page whatever the answer is. It used to hide itself when there
   * was nothing to download, which meant pressing Refresh — the one action guaranteed to
   * empty it — collapsed the page from two panels to one under the reader's cursor. A panel
   * that says "nothing here" is information; a panel that vanishes is a page rearranging
   * itself.
   *
   * Failure is reported in the same place rather than thrown at the page: the library above
   * is the page, and this section is an extra. A service that is asleep should say so here,
   * not put an error across a view that is working.
   */
  async function loadAvailable() {
    var M = window.MCAgents;
    var list = document.getElementById('available-list');
    var count = document.getElementById('available-count');

    try {
      var payload = await M.api('/catalog');

      /* The same request answers both questions: what is published that is not here, and
       * which of the packs that *are* here the service has moved past. Asking twice would
       * be two listings of the same catalog. */
      var behind = {};
      (payload.outdatedItems || []).forEach(function (row) {
        behind[row.id] = row.publishedVersion;
      });
      var changed = JSON.stringify(behind) !== JSON.stringify(state.behind);
      state.behind = behind;
      if (changed && typeof onBehindChanged === 'function') {
        onBehindChanged();
      }

      if (payload.items.length === 0) {
        count.textContent = '';
        M.showState(list, payload.published > 0
          ? 'Everything published is already downloaded.'
          : 'The service publishes nothing yet.');
        return;
      }
      M.replace(list, payload.items.map(availableCard));
      count.textContent =
        payload.items.length + (payload.items.length === 1 ? ' pack' : ' packs');
    } catch (ignored) {
      count.textContent = '';
      /* Forget what was known rather than keep showing it. A badge saying "v2 published"
       * is a claim about the service, and this is the moment the service stopped
       * answering. */
      var hadBadges = Object.keys(state.behind).length > 0;
      state.behind = {};
      if (hadBadges && typeof onBehindChanged === 'function') {
        onBehindChanged();
      }
      M.showState(list, 'The knowledge service is not answering, so there is nothing to '
        + 'show here yet.');
    }
  }

  /* Set once the list exists; re-renders the library so the badges match what was just
   * learned. Declared here rather than inlined so loadAvailable does not need to know what
   * a library list is. */
  var onBehindChanged = null;

  /** How many stored packs the service has moved past. */
  function behindCount() {
    return Object.keys(state.behind).length;
  }

  function describe() {
    if (state.total === 0) {
      countLabel.textContent = '';
      return;
    }
    var shown = Math.min(state.offset, state.total);
    countLabel.textContent = shown + ' of ' + state.total
      + (state.total === 1 ? ' pack' : ' packs');
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
    if (state.categoryId) {
      params.set('categoryId', state.categoryId);
    }
    if (state.availability !== 'any') {
      params.set('availability', state.availability);
    }

    try {
      var page = await M.api('/knowledge?' + params.toString());
      if (run !== state.run) {
        /* The filter changed while this was in flight; its rows belong to a list that no
         * longer exists. Appending them would interleave two searches. */
        return;
      }
      if (state.offset === 0) {
        list.textContent = '';
      }
      page.items.forEach(function (item) { list.appendChild(card(item)); });

      state.offset = page.nextOffset;
      state.total = page.total;
      state.hasMore = page.hasMore;
      describe();

      if (page.total === 0) {
        M.showState(list, state.query || state.categoryId
          ? 'No packs match that.'
          : 'No Knowledge Packs downloaded yet.');
      }
    } catch (error) {
      if (run === state.run && state.offset === 0) {
        window.MCAgents.showState(list, error.message, 'error');
      }
    } finally {
      if (run === state.run) {
        state.loading = false;
      }
    }
    // A tall window can show more than one window of cards, and the observer will not fire
    // again while the sentinel never left view.
    if (run === state.run && state.hasMore && sentinelVisible && sentinelVisible()) {
      await loadMore();
    }
  }

  /**
   * The box the sentinel is measured against.
   *
   * Decided by whether the panel is *meant* to scroll, not by whether it currently does.
   * This runs before a single card has loaded, so an emptiness test would say "does not
   * overflow" every time and silently root the observer on the viewport instead.
   *
   * Below the breakpoint the panel stops being a scroller and the list flows with the page,
   * so the page is the right root there.
   */
  function scrollRoot() {
    return scroller && getComputedStyle(scroller).overflowY === 'auto' ? scroller : null;
  }

  function reset() {
    state.run += 1;
    state.offset = 0;
    state.total = 0;
    state.hasMore = true;
    state.loading = false;
    window.MCAgents.showState(list, 'Loading…');
    loadMore();
  }

  async function loadCategories() {
    var M = window.MCAgents;
    try {
      var payload = await M.api('/categories');
      // Cleared first: a refresh calls this again, and appending would show every category
      // twice. Only the category chips go — the free and premium ones live in the markup.
      chips.querySelectorAll('.chip[data-category-id]').forEach(function (chip) {
        chip.remove();
      });
      payload.items.forEach(function (category) {
        var chip = M.el('button', 'chip', category.displayName);
        chip.type = 'button';
        chip.dataset.categoryId = String(category.id);
        chips.appendChild(chip);
      });
    } catch (ignored) {
      /* The filter is a convenience; the list still works without it. */
    }
  }

  function wireFilters() {
    var M = window.MCAgents;

    searchBox.addEventListener('input', M.debounce(function () {
      state.query = searchBox.value.trim();
      reset();
    }, 180));

    chips.addEventListener('click', function (event) {
      var chip = event.target.closest('.chip');
      if (!chip) {
        return;
      }
      chips.querySelectorAll('.chip').forEach(function (other) {
        other.classList.toggle('is-active', other === chip && !chip.classList.contains('is-active'));
      });
      var active = chip.classList.contains('is-active');
      state.categoryId = active ? (chip.dataset.categoryId || '') : '';
      state.availability = active ? (chip.dataset.availability || 'any') : 'any';
      reset();
    });
  }

  document.addEventListener('mcagents:ready', async function () {
    var M = window.MCAgents;
    list = document.getElementById('knowledge-list');
    scroller = document.getElementById('pack-scroller');
    sentinel = document.getElementById('scroll-sentinel');
    countLabel = document.getElementById('knowledge-count');
    searchBox = document.getElementById('knowledge-search');
    chips = document.getElementById('knowledge-filters');

    wireFilters();
    await loadCategories();
    // Rooted on the panel the list actually scrolls inside. Watched against the viewport
    // it would read as permanently visible and the list would load every pack at once —
    // the same failure the chat sidebar had.
    sentinelVisible = window.MCAgents.onVisible(sentinel, loadMore, scrollRoot());

    // Said before the health check answers, so the panel is never a blank box while the
    // service is being woken up.
    M.showState(document.getElementById('available-list'), 'Loading…');

    /* A catalog read can change which cards need a badge, and the cards may already be on
     * screen — the two lists load independently and the local one is always faster. */
    onBehindChanged = reset;

    /* The catalog gets its own button. It is the half of this page that depends on the
     * service, so it is the half a reader will want to try again after the banner clears —
     * and making them reload the library to do it would re-read a table that had not
     * changed. */
    var availableButton = document.getElementById('refresh-available');
    var availableNote = document.getElementById('available-note');
    availableButton.addEventListener('click', async function () {
      availableButton.disabled = true;
      availableNote.textContent = 'Checking…';
      availableNote.className = 'sync-note';
      try {
        await loadAvailable();
        availableNote.textContent = '';
      } catch (error) {
        availableNote.textContent = error.message;
        availableNote.className = 'sync-note is-error';
      } finally {
        availableButton.disabled = false;
      }
    });

    var refreshButton = document.getElementById('refresh-knowledge');
    var note = document.getElementById('sync-note');

    /* Re-reads both lists and downloads nothing.
     *
     * It used to fetch every pack the service published that this machine did not have,
     * which is not what "refresh" means anywhere else and is not what a reader wants from a
     * button next to a search box. Taking a pack is done from that pack's page, one at a
     * time, where they can see what they are taking. */
    refreshButton.addEventListener('click', async function () {
      refreshButton.disabled = true;
      note.textContent = 'Reloading…';
      note.className = 'sync-note';
      try {
        await loadCategories();
        /* The catalog first: it is what says which stored packs are behind, and the list
         * below has to be rendered knowing that or every card would be drawn twice. */
        await loadAvailable();
        reset();
        var stale = behindCount();
        note.textContent = stale === 0
          ? 'Reloaded. Every pack you have is the published version.'
          : 'Reloaded. ' + stale + (stale === 1 ? ' pack has' : ' packs have')
            + ' a newer version published.';
        note.className = 'sync-note' + (stale === 0 ? ' is-ok' : '');
      } catch (error) {
        note.textContent = error.message;
        note.className = 'sync-note is-error';
      } finally {
        refreshButton.disabled = false;
      }
    });

    /* Reachability changes what the second panel can show, not whether this button works:
     * reloading the local library needs nothing but the local library. */
    window.MCAgentsService.onChange(function () {
      loadAvailable();
    });
    window.MCAgentsService.start();
    reset();
    searchBox.focus();
  });
})();
