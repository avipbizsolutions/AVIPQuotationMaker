/**
 * AVIP Quotation Maker – receives site visits and sales-executive sign-ups from the app.
 *
 * Setup (one time):
 * 1. Open the Google Sheet that should hold the visit log > Extensions > Apps Script,
 *    paste this file, Save.
 * 2. Deploy > New deployment > type "Web app"
 *      Execute as: Me    Who has access: Anyone
 * 3. Copy the web app URL (ends in /exec) into the price sheet's "App Settings" tab,
 *    key "visit_log_url". Phones pick it up on their next launch — no app update needed.
 *
 * Tabs "Site Visits" and "Sales Team" are created automatically on the first entry.
 */
const SHARED_KEY = 'avip-quote-7Qm4'; // must match Remote.kt

const VISIT_HEADERS = [
  'Received', 'Visit No', 'Status', 'Sales Executive', 'Exec Mobile', 'Customer', 'WhatsApp',
  'Address', 'District', 'PIN', 'CA No', 'DISCOM', 'Sanctioned Load (kW)', 'Monthly Bill',
  'PAN', 'Aadhaar (last 4)', 'Roof', 'Roof Size', 'Shading', 'GPS', 'Map',
  'System Type', 'System', 'kWp', 'List Price', 'Discount', 'Final Price', 'Subsidy', 'Notes', 'App Version',
];
const TEAM_HEADERS = ['Updated', 'Name', 'Mobile', 'Employee Code', 'Install ID', 'App Version'];

function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    const d = JSON.parse(e.postData.contents);
    if (d.key !== SHARED_KEY) return reply({ ok: false, error: 'unauthorised' });
    lock.waitLock(10000);
    const book = SpreadsheetApp.getActiveSpreadsheet();

    if (d.kind === 'visit') {
      const sheet = tab(book, 'Site Visits', VISIT_HEADERS);
      const row = [
        new Date(), clean(d.number), clean(d.status), clean(d.officer), clean(d.officerMobile),
        clean(d.customer), clean(d.mobile), clean(d.address, 300), clean(d.district), clean(d.pincode),
        clean(d.caNo), clean(d.discom), clean(d.sanctionedLoad), clean(d.monthlyBill),
        clean(d.pan), clean(d.aadhaarLast4), clean(d.roof), clean(d.roofSize), clean(d.shading),
        clean(d.location), d.location ? 'https://maps.google.com/?q=' + clean(d.location) : '',
        clean(d.systemType), clean(d.system), Number(d.kwp) || 0, Number(d.price) || 0,
        Number(d.discount) || 0, Number(d.finalPrice) || 0, Number(d.subsidy) || 0,
        clean(d.notes, 500), clean(d.appVersion),
      ];
      // Re-created quotations update the same row (matched on Visit No).
      const last = sheet.getLastRow();
      const ids = last > 1 ? sheet.getRange(2, 2, last - 1, 1).getValues().map(r => String(r[0])) : [];
      const i = ids.indexOf(String(row[1]));
      if (i >= 0) sheet.getRange(i + 2, 1, 1, row.length).setValues([row]);
      else sheet.appendRow(row);
    } else if (d.kind === 'officer') {
      const sheet = tab(book, 'Sales Team', TEAM_HEADERS);
      const row = [new Date(), clean(d.name), clean(d.mobile), clean(d.code), clean(d.installId), clean(d.appVersion)];
      const last = sheet.getLastRow();
      const ids = last > 1 ? sheet.getRange(2, 5, last - 1, 1).getValues().map(r => String(r[0])) : [];
      const i = ids.indexOf(String(row[4]));
      if (i >= 0) sheet.getRange(i + 2, 1, 1, row.length).setValues([row]);
      else sheet.appendRow(row);
    } else {
      return reply({ ok: false, error: 'unknown kind' });
    }
    return reply({ ok: true });
  } catch (err) {
    return reply({ ok: false, error: String(err) });
  } finally {
    try { lock.releaseLock(); } catch (_) {}
  }
}

function tab(book, name, headers) {
  let sheet = book.getSheetByName(name);
  if (!sheet) {
    sheet = book.insertSheet(name);
    sheet.appendRow(headers);
    sheet.setFrozenRows(1);
    sheet.getRange(1, 1, 1, headers.length).setFontWeight('bold').setBackground('#E8F3EC');
  }
  return sheet;
}

// Trim, cap length, and stop text being read as a formula.
function clean(value, max) {
  const v = String(value == null ? '' : value).trim().slice(0, max || 150);
  return /^[=+\-@]/.test(v) ? "'" + v : v;
}

function reply(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
