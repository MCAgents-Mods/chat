/*
 * One Knowledge Pack, in full, and read-only.
 *
 * Showcase only: there is no form, no save button, and no endpoint behind this page that
 * could change a pack. A pack is published by the central service and replaced by version,
 * so editing a stored copy would only put the player out of step with the next update.
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

    var badges = document.getElementById('pack-badges');
    M.replace(badges, [
      M.el('span', 'badge badge--accent', 'v' + pack.version),
      M.el('span', 'badge' + (pack.premium ? ' badge--sponsor' : ''),
        pack.premium ? 'Premium' : 'Free'),
      M.el('span', 'badge' + (pack.hasEmbedding ? ' badge--ok' : ' badge--warn'),
        pack.hasEmbedding ? 'Embedded' : 'No embedding')
    ]);

    M.replace(document.getElementById('pack-facts'), [
      row('Identifier', pack.id),
      row('Version', pack.version),
      row('Category', String(pack.categoryId)),
      row('Availability', pack.premium ? 'Premium' : 'Free'),
      row('Downloaded', M.formatDate(pack.createdAt)),
      row('Updated', M.formatDate(pack.updatedAt))
    ]);

    /* textContent, not innerHTML. A pack body is text published by somebody else, and this
     * page renders it rather than trusting it. */
    document.getElementById('pack-detail').textContent =
      pack.rawContent && pack.rawContent.length > 0 ? pack.rawContent : 'This pack has no text.';

    var embedding = document.getElementById('pack-embedding');
    if (pack.hasEmbedding) {
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
    try {
      var pack = await M.api('/knowledge/' + encodeURIComponent(id));
      document.getElementById('pack-state').hidden = true;
      render(pack);
    } catch (error) {
      M.showState(document.getElementById('pack-state'), error.message, 'error');
    }
  });
})();
