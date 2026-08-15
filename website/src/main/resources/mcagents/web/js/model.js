/*
 * Model selection.
 *
 * The suggested ids come from the console rather than being listed here, so this page and
 * the in-game screen cannot drift apart: there is one list, in ModelCatalog, and both read
 * it. Neither validates against it — a provider adds and retires models faster than the mod
 * ships, so an unknown id is accepted and the provider gets to be the one that says no.
 */
(function () {
  'use strict';

  var chatBox;
  var embeddingBox;
  var toggleButton;
  var noteEl;
  var defaults = { chat: '', embedding: '', enabled: true };

  /* Held here rather than read back off the button, so the state is a value and not a
   * class name. Applied on Save like everything else on this page — the chat page flips it
   * immediately because it has no Save button; this page has one, and a switch that ignored
   * it would be the only control here that did. */
  var embeddingEnabled = true;

  function paintToggle() {
    window.MCAgents.paintToggle(toggleButton, embeddingEnabled);
  }

  function note(message, kind) {
    noteEl.textContent = message || '';
    noteEl.className = 'save-note' + (kind ? ' is-' + kind : '');
  }

  /**
   * Offers the suggested ids as chips.
   *
   * Chips only. There was a <datalist> on the field as well, which is worse in every way
   * that matters here: it is invisible until you start typing, so a player who has never
   * seen a model id has nothing to type to reveal it; browsers render and filter it
   * differently from each other; and it looked like a closed dropdown on a field that
   * deliberately accepts anything the provider serves.
   *
   * The chips say the same thing while being visible, and the field stays plain text.
   */
  function fillSuggestions(chipsId, ids, target) {
    var M = window.MCAgents;
    var chips = document.getElementById(chipsId);
    chips.textContent = '';

    ids.forEach(function (id) {
      var chip = M.el('button', 'chip', id);
      chip.type = 'button';
      chip.addEventListener('click', function () {
        target.value = id;
        markActive();
        note('');
      });
      chips.appendChild(chip);
    });
  }

  function markActive() {
    document.querySelectorAll('#chat-suggestions .chip').forEach(function (chip) {
      chip.classList.toggle('is-active', chip.textContent === chatBox.value.trim());
    });
    document.querySelectorAll('#embedding-suggestions .chip').forEach(function (chip) {
      chip.classList.toggle('is-active', chip.textContent === embeddingBox.value.trim());
    });
  }

  async function load() {
    var M = window.MCAgents;
    try {
      var models = await M.api('/models');
      defaults = {
        chat: models.defaultChatModel,
        embedding: models.defaultEmbeddingModel,
        enabled: true
      };

      fillSuggestions('chat-suggestions', models.suggestedChatModels, chatBox);
      fillSuggestions('embedding-suggestions', models.suggestedEmbeddingModels, embeddingBox);

      chatBox.value = models.chatModel;
      embeddingBox.value = models.embeddingModel;
      chatBox.placeholder = models.defaultChatModel;
      embeddingBox.placeholder = models.defaultEmbeddingModel;
      embeddingEnabled = models.embeddingEnabled;
      paintToggle();
      markActive();
    } catch (error) {
      note(error.message, 'error');
    }
  }

  async function save() {
    try {
      /* Blank is not an error: the console substitutes the default, which is how a player
       * gets back to it by hand. What comes back is what was stored, which may differ from
       * what was typed. */
      var stored = await window.MCAgents.api('/models', {
        method: 'PUT',
        body: {
          chatModel: chatBox.value.trim(),
          embeddingModel: embeddingBox.value.trim(),
          embeddingEnabled: embeddingEnabled
        }
      });
      chatBox.value = stored.chatModel;
      embeddingBox.value = stored.embeddingModel;
      embeddingEnabled = stored.embeddingEnabled;
      paintToggle();
      markActive();
      note('Saved. The game uses this from the next question onward.', 'ok');
    } catch (error) {
      note(error.message, 'error');
    }
  }

  document.addEventListener('mcagents:ready', async function () {
    chatBox = document.getElementById('chat-model');
    embeddingBox = document.getElementById('embedding-model');
    toggleButton = document.getElementById('embedding-toggle');
    noteEl = document.getElementById('save-note');

    document.getElementById('save').addEventListener('click', save);
    toggleButton.addEventListener('click', function () {
      embeddingEnabled = !embeddingEnabled;
      paintToggle();
      note('Press Save to apply.');
    });
    document.getElementById('reset').addEventListener('click', function () {
      chatBox.value = defaults.chat;
      embeddingBox.value = defaults.embedding;
      embeddingEnabled = defaults.enabled;
      paintToggle();
      markActive();
      note('Defaults filled in. Press Save to keep them.');
    });
    [chatBox, embeddingBox].forEach(function (box) {
      box.addEventListener('input', function () { markActive(); note(''); });
    });

    await load();
  });
})();
