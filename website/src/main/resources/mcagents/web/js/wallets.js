/*
 * Wallets, one panel per account.
 *
 * The MetaMask handshake runs here rather than through the mod's loopback bridge. That
 * bridge exists because a Minecraft screen has no window.ethereum and something has to hand
 * the browser a page; this *is* a page in the browser, so it asks MetaMask directly and
 * posts each signature to /api/wallets/verify. The checking is the same code either way.
 *
 * Every write names its account and the mod checks it. A panel can only ever act on the
 * wallets in the account it is drawing — that check is in the mod, not in this file, because
 * a boundary enforced by the page is a boundary anyone with a fetch() can step over.
 */
(function () {
  'use strict';

  var M;
  var grid;
  var countEl;
  var noteEl;

  var accounts = [];

  /**
   * How many wallet rows a panel shows before it scrolls.
   *
   * Measured rather than assumed. A row is one line for a wallet with no address and four
   * for one with three, so a height in the stylesheet would show five rows in one account
   * and two in the next — which is what a fixed guess did do. The stylesheet keeps a
   * fallback for the moment before this runs and for a browser with scripting off.
   */
  var VISIBLE_ROWS = 5;

  // ------------------------------------------------------------------ data

  async function load() {
    var body = await M.api('/wallets');
    accounts = body.accounts || [];
    countEl.textContent = body.total === 1
      ? '1 wallet in 1 account'
      : body.total + ' wallets in ' + body.accountCount
        + (body.accountCount === 1 ? ' account' : ' accounts');
    render();
  }

  function note(message, kind) {
    noteEl.textContent = message || '';
    noteEl.className = 'muted sync-note' + (kind ? ' is-' + kind : '');
  }

  // --------------------------------------------------------------- drawing

  function render() {
    if (accounts.length === 0) {
      // Accounts have no table of their own: one exists exactly as long as a wallet names
      // it. So an empty page is not an error, it is a player who has not started.
      M.showState(grid, 'No accounts yet. Add one, then connect MetaMask to fill it.');
      return;
    }
    M.replace(grid, accounts.map(drawAccount));
    // After insertion: a row that is not in the document has no height to measure.
    window.requestAnimationFrame(fitAll);
  }

  /** Sizes every scroller to exactly five rows, whatever height its rows turned out to be. */
  function fitAll() {
    var scrollers = document.querySelectorAll('.wallet-scroller');
    for (var i = 0; i < scrollers.length; i++) {
      fitRows(scrollers[i]);
    }
  }

  function fitRows(scroller) {
    var rows = scroller.querySelectorAll('.wallet');
    if (rows.length <= VISIBLE_ROWS) {
      // Nothing to scroll past, so the panel is as tall as it needs to be. Forcing it to
      // five rows' height would leave a short account padded out with empty space.
      scroller.style.maxHeight = '';
      return;
    }

    var last = rows[VISIBLE_ROWS - 1];
    var content = last.offsetTop + last.offsetHeight - rows[0].offsetTop;

    // Everything is border-box here, so max-height has to cover the padding and the border
    // as well or the box would be two rows' worth of chrome short.
    var style = window.getComputedStyle(scroller);
    var chrome = parseFloat(style.paddingTop) + parseFloat(style.paddingBottom)
      + parseFloat(style.borderTopWidth) + parseFloat(style.borderBottomWidth);

    scroller.style.maxHeight = Math.ceil(content + chrome) + 'px';
  }

  function drawAccount(account) {
    var panel = M.el('section', 'account');

    var head = M.el('div', 'account__head');
    head.append(
      M.el('h3', 'account__name', account.id),
      M.el('span', 'count', account.verified + ' of ' + account.total + ' proved')
    );

    var scroller = M.el('div', 'wallet-scroller');
    scroller.tabIndex = 0;
    scroller.setAttribute('role', 'region');
    scroller.setAttribute('aria-label', 'Wallets in ' + account.id);

    if (account.wallets.length === 0) {
      scroller.append(M.el('div', 'state', 'No wallets in this account yet.'));
    } else {
      account.wallets.forEach(function (wallet) {
        scroller.append(drawWallet(account, wallet));
      });
    }

    var status = M.el('p', 'account__note');
    panel.append(head, scroller, drawActions(account, status), status);
    return panel;
  }

  function drawWallet(account, wallet) {
    var row = M.el('div', 'wallet');

    var head = M.el('div', 'wallet__head');
    var label = M.el('span', 'wallet__label', wallet.label);
    // The row shows one line and cuts the rest; this is where a long name stays readable.
    label.title = wallet.label;
    head.append(label);

    // The tick says the player proved this wallet by signing rather than typing it in.
    // Without it a recorded address and a proved one look identical.
    head.append(wallet.verified
      ? M.el('span', 'badge badge--ok', 'Proved')
      : M.el('span', 'badge', 'Not proved'));

    var controls = M.el('div', 'wallet__controls');
    controls.append(
      button('Rename', 'btn btn--small', function () { rename(account, wallet); }),
      button('Delete', 'btn btn--small btn--danger', function () { remove(account, wallet); })
    );
    head.append(controls);
    row.append(head);

    if (wallet.addresses.length > 0) {
      var list = M.el('div', 'wallet__addresses');
      wallet.addresses.forEach(function (address) {
        list.append(drawAddress(account, wallet, address));
      });
      row.append(list);
    }
    return row;
  }

  function drawAddress(account, wallet, address) {
    var line = M.el('div', 'addr');
    line.append(
      M.el('span', 'addr__chain', address.platformName),
      M.el('span', 'addr__value', address.masked)
    );
    // The list shows a shortened form because forty characters do not fit beside a chain
    // name. This is how the player gets the real thing.
    line.append(button('Copy', 'btn btn--small', function () {
      copy(address.address);
    }));
    line.append(button('Remove', 'btn btn--small btn--danger', function () {
      removeAddress(account, wallet, address);
    }));
    return line;
  }

  function drawActions(account, status) {
    var actions = M.el('div', 'account__actions');
    actions.append(
      button('Connect MetaMask', 'btn btn--primary btn--small', function (element) {
        connect(account, status, element);
      }),
      button('Add wallet', 'btn btn--small', function () { addWallet(account); })
    );
    return actions;
  }

  function button(text, className, onClick) {
    var node = M.el('button', className, text);
    node.type = 'button';
    node.addEventListener('click', function () { onClick(node); });
    return node;
  }

  // --------------------------------------------------------------- actions

  async function addAccount() {
    var name = await M.prompt({
      title: 'Add an account',
      message: 'An account is a name you keep wallets under. Naming a new one creates it.',
      confirmLabel: 'Add',
      value: ''
    });
    if (!name || !name.trim()) {
      return;
    }
    await run(function () {
      return M.api('/wallets', { method: 'POST', body: { accountId: name.trim() } });
    });
  }

  async function addWallet(account) {
    var label = await M.prompt({
      title: 'Add a wallet',
      message: 'A wallet in ' + account.id + '. You can rename it later.',
      confirmLabel: 'Add',
      value: ''
    });
    if (label === null) {
      return;
    }
    await run(function () {
      return M.api('/wallets', {
        method: 'POST',
        body: { accountId: account.id, label: label.trim() }
      });
    });
  }

  async function rename(account, wallet) {
    var label = await M.prompt({
      title: 'Rename wallet',
      message: 'What should this wallet be called?',
      value: wallet.label,
      confirmLabel: 'Rename'
    });
    if (label === null) {
      return;
    }
    await run(function () {
      return M.api('/wallets/' + encodeURIComponent(wallet.id), {
        method: 'PUT',
        body: { accountId: account.id, label: label.trim() }
      });
    });
  }

  async function remove(account, wallet) {
    // Deleting a wallet takes every address under it and cannot be undone, so it is the one
    // action here that does not happen on a single click.
    var confirmed = await M.confirm({
      title: 'Delete wallet',
      message: 'Delete "' + wallet.label + '" and every address under it? '
        + 'This cannot be undone.',
      confirmLabel: 'Delete',
      danger: true
    });
    if (!confirmed) {
      return;
    }
    await run(function () {
      return M.api('/wallets/' + encodeURIComponent(wallet.id)
        + '?accountId=' + encodeURIComponent(account.id), { method: 'DELETE' });
    });
  }

  async function removeAddress(account, wallet, address) {
    var confirmed = await M.confirm({
      title: 'Remove address',
      message: 'Remove the ' + address.platformName + ' address from "' + wallet.label + '"?',
      confirmLabel: 'Remove',
      danger: true
    });
    if (!confirmed) {
      return;
    }
    await run(function () {
      return M.api('/wallets/' + encodeURIComponent(wallet.id)
        + '/addresses/' + encodeURIComponent(address.platform)
        + '?accountId=' + encodeURIComponent(account.id), { method: 'DELETE' });
    });
  }

  async function copy(value) {
    try {
      await navigator.clipboard.writeText(value);
      note('Address copied.', 'ok');
    } catch (error) {
      // Clipboard access can be refused, and an address the player cannot copy is one they
      // cannot use — so show it in full instead of failing quietly.
      await M.confirm({
        title: 'Copy this address',
        message: value,
        confirmLabel: 'Close'
      });
    }
  }

  async function run(action) {
    try {
      await action();
      note('');
      await load();
    } catch (error) {
      note(error.message, 'bad');
    }
  }

  // -------------------------------------------------------------- MetaMask

  /**
   * Proves every address MetaMask shares, into this account and no other.
   *
   * One signature per address, each over a message naming that address: a single shared
   * message would let one signature be posted back beside any other address, which is a hole
   * that only opens once a connection carries more than one of them.
   *
   * An address that fails does not take the rest with it. A declined signature on the third
   * of five is a reason to skip it, not to throw away the two already proved.
   */
  async function connect(account, status, element) {
    if (!window.ethereum) {
      say(status, 'No MetaMask found in this browser. Open this console in the browser '
        + 'MetaMask is installed in — the address is in the game window.', 'bad');
      return;
    }

    element.disabled = true;
    say(status, 'Waiting for MetaMask…');

    var addresses;
    try {
      addresses = await window.ethereum.request({ method: 'eth_requestAccounts' });
    } catch (error) {
      element.disabled = false;
      say(status, describe(error), 'bad');
      return;
    }
    if (!addresses || addresses.length === 0) {
      element.disabled = false;
      say(status, 'No address was shared. Nothing was bound.', 'bad');
      return;
    }

    var chainId = '';
    try {
      chainId = await window.ethereum.request({ method: 'eth_chainId' });
    } catch (ignored) {
      // The chain decides which chain the address is filed under, and the mod falls back to
      // Ethereum without it. Not worth failing the binding for.
    }

    var bound = 0;
    var lastError = '';
    for (var i = 0; i < addresses.length; i++) {
      say(status, 'Confirm in MetaMask — ' + (i + 1) + ' of ' + addresses.length + '…');
      try {
        await prove(account, addresses[i], chainId);
        bound += 1;
      } catch (error) {
        lastError = describe(error);
      }
    }

    element.disabled = false;
    if (bound === 0) {
      say(status, lastError || 'Nothing was signed, so nothing was bound.', 'bad');
      return;
    }
    say(status, 'Proved ' + bound + (bound === 1 ? ' address.' : ' addresses.'), 'ok');
    await load();
  }

  async function prove(account, address, chainId) {
    var challenge = await M.api('/wallets/challenge'
      + '?accountId=' + encodeURIComponent(account.id)
      + '&address=' + encodeURIComponent(address));

    // MetaMask expects personal_sign data as hex-encoded UTF-8 and shows the decoded text
    // in its prompt. Passing the raw string works in some builds and not others.
    var signature = await window.ethereum.request({
      method: 'personal_sign',
      params: [toHex(challenge.message), address]
    });

    return M.api('/wallets/verify', {
      method: 'POST',
      body: {
        accountId: account.id,
        nonce: challenge.nonce,
        address: address,
        signature: signature,
        chainId: chainId
      }
    });
  }

  function toHex(text) {
    var bytes = new TextEncoder().encode(text);
    var hex = '0x';
    for (var i = 0; i < bytes.length; i++) {
      hex += bytes[i].toString(16).padStart(2, '0');
    }
    return hex;
  }

  function describe(error) {
    var reason = (error && (error.message || error.reason)) || String(error);
    return reason.indexOf('ser rejected') >= 0
      ? 'You declined in MetaMask.'
      : reason;
  }

  function say(status, message, kind) {
    status.textContent = message || '';
    status.className = 'account__note' + (kind ? ' is-' + kind : '');
  }

  // ------------------------------------------------------------------ boot

  document.addEventListener('mcagents:ready', function () {
    M = window.MCAgents;
    grid = document.getElementById('account-grid');
    countEl = document.getElementById('wallet-count');
    noteEl = document.getElementById('wallet-note');

    document.getElementById('add-account').addEventListener('click', addAccount);
    document.getElementById('refresh-wallets').addEventListener('click', function () {
      // Local only: wallets live on this machine, so this re-reads storage and never
      // touches the network.
      note('');
      load().catch(function (error) { note(error.message, 'bad'); });
    });

    // A narrower window wraps a row onto more lines, so five rows is a different height
    // than it was. Debounced: a drag across the screen is hundreds of resize events.
    window.addEventListener('resize', M.debounce(fitAll, 150));

    load().catch(function (error) {
      M.showState(grid, error.message, 'bad');
    });
  });
})();
