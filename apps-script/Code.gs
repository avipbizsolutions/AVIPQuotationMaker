/**
 * AVIP Quotation Maker – office log for the Sales Head.
 *
 * Receives from every sales executive's phone:
 *   • each submitted site visit (one row per visit, updated when it changes)
 *   • each follow-up call result
 *   • the Quotation PDF, Site Visit Report PDF and voice notes (saved to Google Drive,
 *     linked from the visit row)
 * and keeps a live "Dashboard" tab: visits per executive, today / this month,
 * lead status, follow-ups due and coming up.
 *
 * Setup (one time, from the Sales Head's / office Google account):
 * 1. Create a new Google Sheet, e.g. "AVIP Site Visits Log" (keep it private).
 *    Extensions > Apps Script, paste this file, Save.
 * 2. Run the function "setup" once (Run ▶) and allow the permissions it asks for
 *    (Sheets + Drive). It creates the tabs, the Dashboard and the Drive folder.
 * 3. Deploy > New deployment > type "Web app"
 *      Execute as: Me    Who has access: Anyone
 *    Copy the web app URL (ends in /exec).
 * 4. Paste it in the price sheet's "App Settings" tab, key "visit_log_url".
 *    Phones pick it up on their next launch — no app update needed.
 * When you change this code later: Deploy > Manage deployments > Edit > Version: New version.
 *
 * Team view: anyone marked "Yes" under "Sees all team visits" in the price sheet's
 * "App Users" tab can see every executive's visits in the app (Team Visits screen).
 */
const SHARED_KEY = 'avip-quote-7Qm4'; // must match Remote.kt
const FOLDER_NAME = 'AVIP Site Visits';
const PRICE_SHEET_ID = '1g4CSk_r3YaaBZ9RO4vGIO8n14GrVrKaejB35HNPCjXc'; // "AVIP Customer Prices (App)"
// never sent to phones, even for the team view
const PRIVATE_COLUMNS = ['PAN', 'Aadhaar (last 4)'];

const VISIT_HEADERS = [
  'Received', 'Visit No', 'Visit Date', 'Sales Executive', 'Exec Mobile', 'Customer', 'WhatsApp',
  'Address', 'District', 'PIN', 'CA No', 'DISCOM', 'Sanctioned Load (kW)', 'Monthly Bill',
  'Funding', 'PAN', 'Aadhaar (last 4)', 'Roof', 'Roof Size', 'Shading', 'Structure', 'Map',
  'System Type', 'System', 'kWp', 'List Price', 'Discount', 'Final Price',
  'Central Subsidy', 'Bihar Subsidy', 'Loan Amount', 'EMI', 'Loan Years',
  'Lead Status', 'Next Follow-up', 'Follow-up Note', 'Calls', 'Last Call',
  'Quotation PDF', 'Site Report PDF', 'Voice Notes', 'Site Notes', 'Photos', 'App Version',
];
// column numbers (1-based) used below and in the Dashboard formulas
const COL = {};
VISIT_HEADERS.forEach((h, i) => COL[h] = i + 1);

const FOLLOW_HEADERS = ['Received', 'Call Time', 'Visit No', 'Sales Executive', 'Customer', 'WhatsApp', 'Result', 'Note', 'Next Follow-up', 'Lead Status'];
const TEAM_HEADERS = ['Updated', 'Name', 'Mobile', 'Employee Code', 'Install ID', 'App Version'];

function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    const d = JSON.parse(e.postData.contents);
    if (d.key !== SHARED_KEY) return reply({ ok: false, error: 'unauthorised' });
    if (d.kind === 'team') return reply(teamVisits(SpreadsheetApp.getActiveSpreadsheet(), d)); // read only: no lock
    lock.waitLock(20000);
    const book = SpreadsheetApp.getActiveSpreadsheet();

    if (d.kind === 'visit') saveVisit(book, d);
    else if (d.kind === 'followup') saveFollowUp(book, d);
    else if (d.kind === 'file') saveFile(book, d);
    else if (d.kind === 'officer') saveOfficer(book, d);
    else return reply({ ok: false, error: 'unknown kind' });
    return reply({ ok: true });
  } catch (err) {
    return reply({ ok: false, error: String(err) });
  } finally {
    try { lock.releaseLock(); } catch (_) {}
  }
}

/** Run once from the editor: creates tabs, Dashboard and the Drive folder. */
function setup() {
  const book = SpreadsheetApp.getActiveSpreadsheet();
  tab(book, 'Site Visits', VISIT_HEADERS);
  tab(book, 'Follow-ups', FOLLOW_HEADERS);
  tab(book, 'Sales Team', TEAM_HEADERS);
  dashboard(book, true);
  folder();
}

// ---------------------------------------------------------------- visits

function saveVisit(book, d) {
  const sheet = tab(book, 'Site Visits', VISIT_HEADERS);
  const row = sheet.getLastRow() > 1 ? findRow(sheet, d.number) : -1;
  const keep = row > 0 ? sheet.getRange(row, 1, 1, VISIT_HEADERS.length).getValues()[0] : null;
  const loc = clean(d.location);
  const values = [
    new Date(), clean(d.number), isoDate(d.visitDate), clean(d.officer), clean(d.officerMobile),
    clean(d.customer), clean(d.mobile), clean(d.address, 300), clean(d.district), clean(d.pincode),
    clean(d.caNo), clean(d.discom), clean(d.sanctionedLoad), clean(d.monthlyBill),
    clean(d.funding), clean(d.pan), clean(d.aadhaarLast4), clean(d.roof), clean(d.roofSize), clean(d.shading),
    clean(d.structure), loc ? 'https://maps.google.com/?q=' + loc : '',
    clean(d.systemType), clean(d.system), num(d.kwp), num(d.price), num(d.discount), num(d.finalPrice),
    num(d.centralSubsidy), num(d.stateSubsidy), num(d.loanAmount), Math.round(num(d.emi)), num(d.loanYears) || '',
    clean(d.leadStatus) || 'Open', isoDate(d.followUp), clean(d.followUpNote, 300), num(d.calls), clean(d.lastCall, 300),
    keep ? keep[COL['Quotation PDF'] - 1] : '', keep ? keep[COL['Site Report PDF'] - 1] : '',
    keep ? keep[COL['Voice Notes'] - 1] : '',
    clean(d.notes, 500), num(d.photos), clean(d.appVersion),
  ];
  if (row > 0) sheet.getRange(row, 1, 1, values.length).setValues([values]);
  else sheet.appendRow(values);

  // Converted with an installation date → a Draft site in AVIP Solar Install Check
  if (clean(d.leadStatus) === 'Converted') {
    const m = clean(d.notes, 500).match(/Installation wanted: (\d{4}-\d{2}-\d{2})/);
    if (m) pushToInstallCheck(d, m[1], keep ? keep[COL['Site Report PDF'] - 1] : '');
  }
}

/**
 * Sends a converted visit to AVIP Solar Install Check. Needs two Script Properties
 * (Apps Script → Project Settings → Script Properties):
 *   INSTALL_CHECK_URL = the Install Check web app /exec URL
 *   INSTALL_CHECK_KEY = the link key printed by Install Check's "setup"
 * Without them nothing is sent. A failure never stops the visit from being logged.
 */
function pushToInstallCheck(d, installDate, reportPdf) {
  const p = PropertiesService.getScriptProperties();
  const url = p.getProperty('INSTALL_CHECK_URL');
  const key = p.getProperty('INSTALL_CHECK_KEY');
  if (!url || !key) return;
  try {
    UrlFetchApp.fetch(url, {
      method: 'post', contentType: 'text/plain', muteHttpExceptions: true, followRedirects: true,
      payload: JSON.stringify({
        key: 'avip-install-9Kp2', kind: 'salesVisit', linkKey: key,
        visitNo: clean(d.number), salesPerson: clean(d.officer), salesMobile: clean(d.officerMobile),
        customer: clean(d.customer),
        mobile: clean(d.mobile), address: clean(d.address, 300), district: clean(d.district),
        location: clean(d.location), kwp: num(d.kwp), systemType: clean(d.systemType),
        system: clean(d.system), discom: clean(d.discom), caNo: clean(d.caNo),
        visitDate: clean(d.visitDate), installDate: installDate, reportPdf: clean(reportPdf),
      }),
    });
  } catch (err) {
    console.log('Install Check push failed: ' + err);
  }
}

function saveFollowUp(book, d) {
  tab(book, 'Follow-ups', FOLLOW_HEADERS).appendRow([
    new Date(), clean(d.callDate), clean(d.number), clean(d.officer), clean(d.customer), clean(d.mobile),
    clean(d.outcome), clean(d.note, 500), isoDate(d.nextDate), clean(d.leadStatus),
  ]);
}

function saveOfficer(book, d) {
  const sheet = tab(book, 'Sales Team', TEAM_HEADERS);
  const row = [new Date(), clean(d.name), clean(d.mobile), clean(d.code), clean(d.installId), clean(d.appVersion)];
  const last = sheet.getLastRow();
  const ids = last > 1 ? sheet.getRange(2, 5, last - 1, 1).getValues().map(r => String(r[0])) : [];
  const i = ids.indexOf(String(row[4]));
  if (i >= 0) sheet.getRange(i + 2, 1, 1, row.length).setValues([row]);
  else sheet.appendRow(row);
}

// ---------------------------------------------------------------- team view

/** All visits of the whole team, for numbers allowed in the App Users tab. */
function teamVisits(book, d) {
  const mob = last10(d.mobile);
  if (mob.length !== 10) return { ok: false, error: 'no mobile' };
  // the request must come from a phone registered with that number
  const team = tab(book, 'Sales Team', TEAM_HEADERS);
  const reg = team.getLastRow() > 1 ? team.getRange(2, 1, team.getLastRow() - 1, TEAM_HEADERS.length).getValues() : [];
  if (!reg.some(r => last10(r[2]) === mob && String(r[4]) === String(d.installId))) {
    return { ok: false, error: 'This phone is not registered yet. Open the app with internet and try again in a minute.' };
  }
  const users = SpreadsheetApp.openById(PRICE_SHEET_ID).getSheetByName('App Users').getDataRange().getDisplayValues();
  const me = users.slice(1).find(r => last10(r[0]) === mob && !/^\s*n/i.test(r[2] || '') && /^\s*y/i.test(r[4] || ''));
  if (!me) return { ok: false, error: 'Your number does not have team view. Ask the Sales Head.' };

  const sv = tab(book, 'Site Visits', VISIT_HEADERS);
  const last = sv.getLastRow();
  const rows = last > 1 ? sv.getRange(2, 1, last - 1, VISIT_HEADERS.length).getDisplayValues() : [];
  const visits = rows.filter(r => r[COL['Visit No'] - 1]).slice(-1000).reverse().map(r => {
    const o = {};
    VISIT_HEADERS.forEach((h, i) => { if (PRIVATE_COLUMNS.indexOf(h) < 0 && r[i] !== '') o[h] = r[i]; });
    return o;
  });
  return { ok: true, visits: visits };
}

function last10(v) { return String(v == null ? '' : v).replace(/\D/g, '').slice(-10); }

// ---------------------------------------------------------------- files

function saveFile(book, d) {
  const name = clean(d.name);
  const sub = subFolder(clean(d.number) + ' - ' + clean(d.customer));
  // a re-created PDF replaces the old one
  const old = sub.getFilesByName(name);
  while (old.hasNext()) old.next().setTrashed(true);
  const file = sub.createFile(Utilities.newBlob(Utilities.base64Decode(d.data), d.mime || 'application/octet-stream', name));

  const sheet = tab(book, 'Site Visits', VISIT_HEADERS);
  const row = findRow(sheet, d.number);
  if (row < 0) return;
  let col;
  if (/^Quotation/i.test(name)) col = COL['Quotation PDF'];
  else if (/^Site-Visit/i.test(name)) col = COL['Site Report PDF'];
  else col = COL['Voice Notes'];
  if (col === COL['Voice Notes']) {
    sheet.getRange(row, col).setValue(sub.getUrl()); // folder holds all notes
  } else {
    sheet.getRange(row, col).setValue(file.getUrl());
  }
  // a converted visit's report reaches Install Check too
  if (col === COL['Site Report PDF']) {
    const r = sheet.getRange(row, 1, 1, VISIT_HEADERS.length).getValues()[0];
    const m = String(r[COL['Site Notes'] - 1]).match(/Installation wanted: (\d{4}-\d{2}-\d{2})/);
    if (String(r[COL['Lead Status'] - 1]) === 'Converted' && m) {
      pushToInstallCheck({
        number: r[COL['Visit No'] - 1], officer: r[COL['Sales Executive'] - 1], officerMobile: r[COL['Exec Mobile'] - 1],
        customer: r[COL['Customer'] - 1],
        mobile: r[COL['WhatsApp'] - 1], address: r[COL['Address'] - 1], district: r[COL['District'] - 1],
        location: String(r[COL['Map'] - 1]).replace('https://maps.google.com/?q=', ''), kwp: r[COL['kWp'] - 1],
        systemType: r[COL['System Type'] - 1], system: r[COL['System'] - 1], discom: r[COL['DISCOM'] - 1],
        caNo: r[COL['CA No'] - 1], visitDate: r[COL['Visit Date'] - 1] instanceof Date
          ? Utilities.formatDate(r[COL['Visit Date'] - 1], 'Asia/Kolkata', 'yyyy-MM-dd') : String(r[COL['Visit Date'] - 1]),
      }, m[1], file.getUrl());
    }
  }
}

function folder() {
  const it = DriveApp.getFoldersByName(FOLDER_NAME);
  return it.hasNext() ? it.next() : DriveApp.createFolder(FOLDER_NAME);
}

function subFolder(name) {
  const parent = folder();
  const it = parent.getFoldersByName(name);
  return it.hasNext() ? it.next() : parent.createFolder(name);
}

// ---------------------------------------------------------------- dashboard

function dashboard(book, rebuild) {
  let sh = book.getSheetByName('Dashboard');
  if (sh && !rebuild) return;
  if (!sh) sh = book.insertSheet('Dashboard', 0);
  sh.clear();
  const L = c => columnLetter(COL[c]);
  const SV = "'Site Visits'!A2:" + columnLetter(VISIT_HEADERS.length);
  const today = '"&TEXT(TODAY(),"yyyy-mm-dd")&"';
  const monthStart = '"&TEXT(EOMONTH(TODAY(),-1)+1,"yyyy-mm-dd")&"';
  const in7 = '"&TEXT(TODAY()+7,"yyyy-mm-dd")&"';
  // Column letters inside QUERY must be the column's position letter (A, B …) of the range.
  const q = c => L(c);

  const blocks = [
    ['A1', 'AVIP Solar — Sales Dashboard (updates live)'],
    ['A3', 'Visits by sales executive — this month'],
    ['A4', '=IFERROR(QUERY(' + SV + ',"select ' + q('Sales Executive') + ', count(' + q('Visit No') + '), sum(' + q('kWp') + '), sum(' + q('Final Price') +
      ') where ' + q('Visit Date') + ' >= date \'' + monthStart + '\' group by ' + q('Sales Executive') +
      ' order by count(' + q('Visit No') + ') desc label ' + q('Sales Executive') + ' \'Executive\', count(' + q('Visit No') + ') \'Visits\', sum(' + q('kWp') +
      ') \'kWp quoted\', sum(' + q('Final Price') + ') \'Value quoted (₹)\'",0),"No visits this month yet")'],
    ['F3', 'Visits today'],
    ['F4', '=IFERROR(QUERY(' + SV + ',"select ' + q('Sales Executive') + ', count(' + q('Visit No') + ') where ' + q('Visit Date') +
      ' = date \'' + today + '\' group by ' + q('Sales Executive') + ' label ' + q('Sales Executive') + ' \'Executive\', count(' + q('Visit No') + ') \'Today\'",0),"None today")'],
    ['I3', 'Lead status by executive (all time)'],
    ['I4', '=IFERROR(QUERY(' + SV + ',"select ' + q('Sales Executive') + ', count(' + q('Visit No') + ') where ' + q('Visit No') +
      ' is not null group by ' + q('Sales Executive') + ' pivot ' + q('Lead Status') + ' label ' + q('Sales Executive') + ' \'Executive\'",0),"")'],
    ['A14', 'Follow-ups DUE (today or overdue)'],
    ['A15', '=IFERROR(QUERY(' + SV + ',"select ' + [q('Next Follow-up'), q('Sales Executive'), q('Customer'), q('WhatsApp'), q('System'), q('Final Price'), q('Follow-up Note'), q('Last Call'), q('Visit No')].join(', ') +
      ' where ' + q('Lead Status') + ' = \'Open\' and ' + q('Next Follow-up') + ' <= date \'' + today + '\' order by ' + q('Next Follow-up') +
      ' label ' + q('Next Follow-up') + ' \'Due\', ' + q('Sales Executive') + ' \'Executive\'",0),"Nothing due 🎉")'],
    ['A40', 'Follow-ups in the next 7 days'],
    ['A41', '=IFERROR(QUERY(' + SV + ',"select ' + [q('Next Follow-up'), q('Sales Executive'), q('Customer'), q('WhatsApp'), q('System'), q('Final Price'), q('Follow-up Note')].join(', ') +
      ' where ' + q('Lead Status') + ' = \'Open\' and ' + q('Next Follow-up') + ' > date \'' + today + '\' and ' + q('Next Follow-up') + ' <= date \'' + in7 + '\' order by ' + q('Next Follow-up') +
      ' label ' + q('Next Follow-up') + ' \'Date\', ' + q('Sales Executive') + ' \'Executive\'",0),"None scheduled")'],
  ];
  blocks.forEach(([cell, v]) => {
    const r = sh.getRange(cell);
    if (String(v).startsWith('=')) r.setFormula(v); else r.setValue(v).setFontWeight('bold').setFontColor('#0E6B3A');
  });
  sh.getRange('A1').setFontSize(16);
  sh.getRange('A15:A39').setNumberFormat('dd-mmm-yyyy');
  sh.getRange('A41:A60').setNumberFormat('dd-mmm-yyyy');
  sh.setFrozenRows(1);
  sh.setColumnWidths(1, 12, 130);
}

// ---------------------------------------------------------------- helpers

function tab(book, name, headers) {
  let sheet = book.getSheetByName(name);
  if (!sheet) {
    sheet = book.insertSheet(name);
    sheet.appendRow(headers);
    sheet.setFrozenRows(1);
    sheet.getRange(1, 1, 1, headers.length).setFontWeight('bold').setBackground('#E8F3EC');
    if (name === 'Site Visits') {
      sheet.getRange('C:C').setNumberFormat('dd-mmm-yyyy');
      sheet.getRange(1, COL['Next Follow-up'], sheet.getMaxRows(), 1).setNumberFormat('dd-mmm-yyyy');
      dashboard(book, false);
    }
  }
  return sheet;
}

function findRow(sheet, number) {
  const last = sheet.getLastRow();
  if (last < 2) return -1;
  const ids = sheet.getRange(2, COL['Visit No'], last - 1, 1).getValues().map(r => String(r[0]));
  const i = ids.indexOf(String(number));
  return i >= 0 ? i + 2 : -1;
}

function isoDate(s) {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(s || ''));
  return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : '';
}

function columnLetter(n) {
  let s = '';
  while (n > 0) { const m = (n - 1) % 26; s = String.fromCharCode(65 + m) + s; n = Math.floor((n - 1) / 26); }
  return s;
}

function num(v) { return Number(v) || 0; }

// Trim, cap length, and stop text being read as a formula.
function clean(value, max) {
  const v = String(value == null ? '' : value).trim().slice(0, max || 150);
  return /^[=+\-@]/.test(v) ? "'" + v : v;
}

function reply(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
