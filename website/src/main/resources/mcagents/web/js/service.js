/*
 * The central service is allowed to be asleep.
 *
 * It is hosted somewhere that idles, so the first request after a quiet period is usually a
 * refused connection and the second, a minute later, succeeds. This module treats that as
 * the ordinary case: it shows a banner rather than an error, retries once a minute, and
 * gives up after ten — because a page that retried forever would poll a host that is not
 * coming back until the tab is closed.
 *
 * Nothing here blocks the page. Every list, every conversation, and every key works while
 * the service is unreachable, because none of them needs it.
 */
(function () {
  'use strict';

  var RETRY_MS = 60 * 1000;
  var GIVE_UP_MS = 10 * 60 * 1000;

  var state = {
    reachable: false,
    attempts: 0,
    startedAt: 0,
    timer: null,
    stopped: false
  };

  var listeners = [];
  var banner;

  function announce() {
    listeners.forEach(function (listener) { listener(state.reachable); });
  }

  /** Runs `listener(reachable)` now and on every change. */
  function onChange(listener) {
    listeners.push(listener);
    listener(state.reachable);
  }

  function ensureBanner() {
    if (banner) {
      return banner;
    }
    var M = window.MCAgents;
    banner = M.el('div', 'callout callout--warn service-banner');
    banner.appendChild(M.el('span', 'callout__icon', '☁'));

    var body = M.el('div', 'service-banner__body');
    body.appendChild(M.el('div', 'service-banner__text', ''));
    banner.appendChild(body);

    var retry = M.el('button', 'btn btn--small', 'Reconnect');
    retry.type = 'button';
    retry.addEventListener('click', function () { reconnect(); });
    banner.appendChild(retry);

    var main = document.querySelector('main .container');
    if (main) {
      main.insertBefore(banner, main.firstChild);
    }
    return banner;
  }

  function show(message, options) {
    var settings = options || {};
    var node = ensureBanner();
    node.querySelector('.service-banner__text').textContent = message;
    node.querySelector('.btn').hidden = !settings.canRetry;
    node.hidden = false;
  }

  /** The banner exists to say something is wrong; when nothing is, it goes away entirely. */
  function hide() {
    if (banner) {
      banner.hidden = true;
    }
  }

  function elapsed() {
    return Date.now() - state.startedAt;
  }

  async function check() {
    var M = window.MCAgents;
    state.attempts += 1;
    try {
      var health = await M.api('/service/health');
      if (health.reachable) {
        state.reachable = true;
        state.stopped = true;
        window.clearTimeout(state.timer);
        hide();
        announce();
        return true;
      }
      waitingOrGiveUp(health.detail);
    } catch (error) {
      waitingOrGiveUp(error.message);
    }
    announce();
    return false;
  }

  function waitingOrGiveUp(detail) {
    state.reachable = false;

    if (elapsed() >= GIVE_UP_MS) {
      state.stopped = true;
      window.clearTimeout(state.timer);
      show('The knowledge service has not answered in ten minutes, so checking has stopped. '
        + 'Everything already downloaded still works.', { canRetry: true });
      return;
    }
    var minutes = Math.max(1, Math.round((GIVE_UP_MS - elapsed()) / 60000));
    show('The knowledge service is not answering yet — it may be waking up. Checking again '
      + 'every minute for another ' + minutes + ' minute' + (minutes === 1 ? '' : 's') + '. '
      + (detail ? '(' + detail + ') ' : '')
      + 'Everything already downloaded still works.', { canRetry: true });

    window.clearTimeout(state.timer);
    state.timer = window.setTimeout(check, RETRY_MS);
  }

  /** The button. Restarts the ten-minute budget, because the player is asking again. */
  function reconnect() {
    window.clearTimeout(state.timer);
    state.stopped = false;
    state.startedAt = Date.now();
    state.attempts = 0;
    show('Checking…', { canRetry: false });
    return check();
  }

  function start() {
    state.startedAt = Date.now();
    return check();
  }

  window.MCAgentsService = {
    start: start,
    reconnect: reconnect,
    onChange: onChange,
    isReachable: function () { return state.reachable; }
  };
})();
