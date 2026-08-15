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
    run: 0
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
    more.appendChild(M.el('span', '', M.formatDate(item.updatedAt)));
    node.appendChild(more);

    return node;
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
    reset();
    searchBox.focus();
  });
})();
