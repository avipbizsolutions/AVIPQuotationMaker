# AVIP Quotation Maker

Android app for the **AVIP Solar** sales team (AVIP Business Solutions LLP). The sales executive
does the whole site visit on the phone at the customer's home and hands over a branded
quotation before leaving — no more filling the paper form and sending the quote from office.

**Download the latest APK:**
https://github.com/avipbizsolutions/AVIPQuotationMaker/releases/latest/download/AVIP-Quotation-Maker.apk

## The visit, step by step
1. **Customer** — name, WhatsApp number, address, district, PIN, DISCOM (NBPDCL / SBPDCL),
   CA number, single / three phase, sanctioned load, monthly bill, PAN, Aadhaar.
2. **Site** — GPS tag of the roof, roof type (RCC / tin shed / asbestos / ground), building
   height, shadow-free length × width (shows how many kW fit), shading, structure height,
   cable run, site photos (camera or gallery), a finger sketch with N / E / S / W, the proposed
   structure (front legs, back legs, rafters, purlins: qty, length, size) and notes.
3. **System** — On-grid / Hybrid, capacity chips and big picture cards (Saathi green, Smart
   blue, Executive gold) with panels, inverter, battery, warranties, price and subsidy. The app
   marks the **Best fit** from the sanctioned load and bill.
4. **Price** — discount slider (up to the sheet's *Maximum Discount*), final price,
   PM Surya Ghar subsidy (switchable), effective cost, units and money saved per month,
   payback and 25-year savings.
5. **Finish** — summary, customer signs on screen, **Create Quotation** makes two PDFs:
   - **Quotation** (for the customer): letterhead, customer, a drawing of how the system
     connects (panels → inverter → battery → home → net meter → grid), bill of materials with
     brands and warranties, price / discount / subsidy / effective cost, savings, the
     PM Surya Ghar steps, bank details, terms and both signatures.
   - **Site Visit Report** (for the office): the paper form, filled — KYC, proposed system,
     GPS with map link, roof details, sketch, structure table, notes, all photos, signatures.

   One tap sends the quotation to the customer's WhatsApp; another shares both PDFs with
   the office. Visits are saved as you go; unfinished ones show as **Draft** on the home screen.

## Prices come from the Google Sheet
The app reads only the customer-price tabs of the sheet (must be *Anyone with the link → Viewer*):

| Tab | Used for |
|---|---|
| **Ongrid Project** | One column per on-grid system. Rows are read by their names, so keep labels such as *Panel Brand*, *Inverter Brand and Specification*, *DCR Panel Wattage* + *No. of Panels*, *Non-DCR Panel Wattage* + *No. of Panels*, *Total Panel Wattage*, *Price (GST Included)*, *Maximum Discount*. New rows you add appear on the quotation automatically. |
| **Hybrid Project** | Same layout for hybrid systems (*Hybrid Inverter*, *Lithium Battery*, *Battery Warranty* …). |
| **App Settings** *(optional)* | Key / Value overrides — see below. |

The costing tabs are never read by the app. Add a system by adding a column; change a price
by editing the cell. Phones pick up changes on next launch (or the refresh button on home).
The app keeps the last copy, so it works without signal at site.

### App Settings keys (all optional — the app has sensible defaults)
`company_name`, `brand_name`, `tagline`, `phone`, `whatsapp`, `email`, `website`, `gstin`,
`address`, `bank_account_name`, `bank_account_no`, `bank_ifsc`, `bank_branch`,
`quote_validity_days` (7), `subsidy_per_kw_first_2` (30000), `subsidy_per_kw_3rd` (18000),
`subsidy_max` (78000), `subsidy_hybrid` (yes), `units_per_kw_per_day` (4),
`tariff_per_unit` (7), `sqft_per_kw` (100), `installs_count` (300+),
`q_terms_1` … `q_terms_12` (quotation terms), `announcement` (home-screen notice),
`visit_log_url` (see below).

## Visit log in Google Sheets (one-time setup)
1. Open the Google Sheet that should hold the log → Extensions → Apps Script → paste
   `apps-script/Code.gs` → Save.
2. Deploy → New deployment → Web app → Execute as **Me**, access **Anyone** → Deploy.
3. Put the `/exec` URL in **App Settings → visit_log_url**.

Every quotation then adds (or updates) a row in **Site Visits**, and each sales executive's
sign-up lands in **Sales Team**.

## Build
Every push to `main` builds a signed APK with GitHub Actions and publishes it as the latest
release; installed apps update themselves on next launch. Keep `app/release.keystore` in
the repo — every update must be signed with it.
