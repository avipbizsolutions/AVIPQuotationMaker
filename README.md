# AVIP Surya Sales

Android app for the **AVIP Solar** sales team (AVIP Business Solutions LLP). The sales executive
does the whole site visit on the phone at the customer's home and hands over a branded
quotation before leaving — no more filling the paper form and sending the quote from office.

**Download the latest APK:**
https://github.com/avipbizsolutions/AVIPQuotationMaker/releases/latest/download/AVIP-Surya-Sales.apk

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
The app reads **AVIP Customer Prices (App)** (`1g4CSk_r3YaaBZ9RO4vGIO8n14GrVrKaejB35HNPCjXc`), which must be
*Anyone with the link → Viewer*. It holds only what customers may see:

| Tab | Used for |
|---|---|
| **Ongrid Project** | Pulled live from the *Updated Costing Sheet* with IMPORTRANGE — do not type here. One column per on-grid system; rows are read by their names (*Panel Brand*, *DCR Panel Wattage* + *No. of Panels*, *Price (GST Included)*, *Maximum Discount* …). |
| **Hybrid Project** | Same, for hybrid systems. |
| **App Settings** | Key / Value settings — see below. |

The team keeps editing prices in the *Updated Costing Sheet* as before; that sheet can (and should)
stay **private**, because it holds cost and margin. Phones pick up price changes on next launch
(or the refresh button on home) and keep the last copy, so the app works without signal at site.

### App Settings keys (all optional — the app has sensible defaults)
`company_name`, `brand_name`, `tagline`, `phone`, `whatsapp`, `email`, `website`, `gstin`,
`address`, `bank_account_name`, `bank_account_no`, `bank_ifsc`, `bank_branch`,
`quote_validity_days` (7), `subsidy_per_kw_first_2` (30000), `subsidy_per_kw_3rd` (18000),
`subsidy_max` (78000), `subsidy_hybrid` (yes), `state_subsidy` (20000), `state_subsidy_name` (Bihar Govt. subsidy),
`loan_rate_slab` (200000), `loan_rate_upto_slab` (6), `loan_rate_above_slab` (8.25), `loan_max_years` (10), `name_transfer_fee` (1500),
`q_loan_terms_1` … (extra terms printed only on bank-loan quotations), `units_per_kw_per_day` (4),
`tariff_per_unit` (7), `sqft_per_kw` (100), `installs_count` (300+),
`q_terms_1` … `q_terms_12` (quotation terms), `announcement` (home-screen notice),
`visit_log_url` (see below).

## Sales Head: team log, follow-ups and dashboard (one-time setup)
Every **submitted** visit (quotation created) from every phone lands in one private Google Sheet,
with the Quotation PDF, Site Visit Report and voice notes saved in Google Drive and linked.

1. From the Sales Head / office Google account, create a new Google Sheet ("AVIP Site Visits Log").
2. Extensions → Apps Script → paste `apps-script/Code.gs` → Save → run **setup** once and allow access.
3. Deploy → New deployment → Web app → Execute as **Me**, access **Anyone** → Deploy.
4. Put the `/exec` URL in the price sheet's **App Settings → visit_log_url**.

Tabs you get:
| Tab | What it shows |
|---|---|
| **Dashboard** | Visits per executive this month (count, kWp, value), visits today, Open / Converted / Lost per executive, follow-ups due today or overdue, follow-ups in the next 7 days. Updates live. |
| **Site Visits** | One row per visit: executive, customer, system, price, central + Bihar subsidy, loan & EMI, lead status, next follow-up, last call result, and links to the PDFs and voice notes. Use Data → Create a filter to slice by executive or date. |
| **Follow-ups** | Every follow-up call logged in the app: who called, result, note, next date. |
| **Sales Team** | Each executive's phone, name and app version. |

Files are saved in Drive under **AVIP Site Visits / <visit no> - <customer>**.

## Build
Every push to `main` builds a signed APK with GitHub Actions and publishes it as the latest
release; installed apps update themselves on next launch. Keep `app/release.keystore` in
the repo — every update must be signed with it.
