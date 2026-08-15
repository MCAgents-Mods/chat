/*
 * One Knowledge Pack, in full.
 *
 * The pack's *content* is read-only: there is no form, no save button, and no endpoint
 * behind this page that could change a pack. A pack is published by the central service and
 * replaced by version, so editing a stored copy would only put the player out of step with
 * the next update.
 *
 * Downloading is a different thing and is offered here. This page opens for a pack the
 * player does not have — the console falls back to the service to show it — and that is the
 * one place a download button belongs, because it is the only page that is about one pack.
 */
(function () {
  'use strict';

  /** The id is the last path segment: /knowledge/<id>. */
  function packId() {
    var parts = window.location.pathname.split('/').filter(function (part) {
      return part.length > 0;
    });
    return parts.length > 1 ? decodeURIComponent(parts[parts.length - 1]) : '';
  }

  function row(term, value) {
    var M = window.MCAgents;
    var wrapper = M.el('div', 'deflist__row');
    wrapper.appendChild(M.el('div', 'deflist__term', term));
    wrapper.appendChild(M.el('div', 'deflist__value', value));
    return wrapper;
  }

  function render(pack) {
    var M = window.MCAgents;

    document.title = (pack.title || pack.id) + ' · MCAgents Console';
    document.getElementById('pack-title').textContent = pack.title || pack.id;
    document.getElementById('breadcrumb-current').textContent = pack.title || pack.id;

    var badges = [
      M.el('span', 'badge badge--accent', 'v' + pack.version),
      M.el('span', 'badge' + (pack.premium ? ' badge--sponsor' : ''),
        pack.premium ? 'Premium' : 'Free')
    ];
    /* An undownloaded pack has no embedding by definition — nothing has embedded it — so
     * saying "No embedding" about it would report a problem where there is none. */
    badges.push(pack.stored
      ? M.el('span', 'badge' + (pack.hasEmbedding ? ' badge--ok' : ' badge--warn'),
        pack.hasEmbedding ? 'Embedded' : 'No embedding')
      : M.el('span', 'badge badge--warn', 'Not downloaded'));
    M.replace(document.getElementById('pack-badges'), badges);

    M.replace(document.getElementById('pack-facts'), [
      row('Identifier', pack.id),
      row('Version', pack.version),
      row('Category', String(pack.categoryId)),
      row('Availability', pack.premium ? 'Premium' : 'Free'),
      row(pack.stored ? 'Downloaded' : 'Published', M.formatDate(pack.createdAt)),
      row('Updated', M.formatDate(pack.updatedAt))
    ]);

    document.getElementById('download-panel').hidden = pack.stored;
    /* The embedding section is about a stored pack's vector. There is nothing truthful to
     * put in it for a pack that has not been downloaded. */
    document.getElementById('embedding-panel').hidden = !pack.stored;

    /* textContent, not innerHTML. A pack body is text published by somebody else, and this
     * page renders it rather than trusting it. */
    document.getElementById('pack-detail').textContent =
      pack.rawContent && pack.rawContent.length > 0 ? pack.rawContent : 'This pack has no text.';

    var embedding = document.getElementById('pack-embedding');
    if (!pack.stored) {
      embedding.textContent = '';
    } else if (pack.hasEmbedding) {
      embedding.textContent = pack.embeddedData;
      document.getElementById('embedding-size').textContent =
        pack.embeddedData.length.toLocaleString() + ' characters as stored';
    } else {
      embedding.textContent = 'No embedding is stored for this pack, so it cannot take part '
        + 'in retrieval.';
      document.getElementById('embedding-size').textContent = '';
    }

    document.getElementById('pack-body').hidden = false;
  }

  document.addEventListener('mcagents:ready', async function () {
    var M = window.MCAgents;
    var id = packId();

    document.getElementById('breadcrumb-list').href = M.link('knowledge/');

    if (!id) {
      M.showState(document.getElementById('pack-state'), 'No pack was named.', 'error');
      return;
    }
    async function load() {
      var pack = await M.api('/knowledge/' + encodeURIComponent(id));
      document.getElementById('pack-state').hidden = true;
      render(pack);
    }

    var button = document.getElementById('download-pack');
    var note = document.getElementById('download-note');
    button.addEventListener('click', async function () {
      button.disabled = true;
      note.textContent = 'Downloading…';
      note.className = 'sync-note';
      try {
        await M.api('/knowledge/' + encodeURIComponent(id) + '/download', { method: 'POST' });
        /* Re-read rather than patching the page from the reply: the pack is now stored, and
         * the stored copy is what every other part of this page is describing. */
        await load();
        note.textContent = 'Downloaded. Embed it from the chat page to let the agent use it.';
        note.className = 'sync-note is-ok';
      } catch (error) {
        note.textContent = error.message;
        note.className = 'sync-note is-error';
      } finally {
        button.disabled = false;
      }
    });

    try {
      await load();
    } catch (error) {
      M.showState(document.getElementById('pack-state'), error.message, 'error');
    }
  });
})();
