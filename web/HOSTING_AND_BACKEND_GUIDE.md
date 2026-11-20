# Panduan Hosting, Penandatanganan APK/AAB, Pembaruan, & Backend SocioDetective

Dokumen ini menjelaskan langkah operasional nyata untuk menerbitkan **SocioDetective** (versi **Android APK**, **AAB**, dan **Web/PWA**) melalui tautan HTTPS publik yang bisa dibagikan ke guru dan siswa.

---

## 1. Struktur Paket Web & PWA (`/web/`)

Folder `/web/` siap diunggah langsung ke layanan hosting HTTPS statis mana pun (gratis maupun berbayar):
- `index.html` — Aplikasi Web & PWA SocioDetective (termasuk musik opening screen, materi NTB, Detective Game, Kuis Guru, dan pemeriksa versi).
- `download.html` — Halaman publik untuk mengunduh `SocioDetective.apk` lengkap dengan panduan izin pemasangan Android.
- `manifest.webmanifest` — Konfigurasi Web App Manifest (ikon, splash color `#0C2249`, mode `standalone`).
- `sw.js` — Service Worker untuk caching aset penting dan pemuatan cepat.
- `version.json` — Metadata versi terbaru yang dibaca oleh pemeriksa pembaruan di dalam aplikasi Android & halaman unduhan.
- `icons/` — Ikon resmi SocioDetective (`192x192`, `512x512`, `maskable 512x512`).
- `downloads/` — Tempat meletakkan file `SocioDetective.apk` hasil build release bertanda tangan.

---

## 2. Cara Membuat Signed Release APK & AAB

Di lingkungan AI Studio atau Android Studio:

1. **Menggunakan AI Studio (Settings / Export):**
   - Gunakan menu ekspor/build AI Studio untuk menghasilkan **Signed Release APK** dan **Android App Bundle (AAB)** (atau ekspor proyek ZIP / push ke GitHub untuk dibuild di Android Studio).
2. **Menggunakan Android Studio (Lokal / CI):**
   - Buka proyek Android ini di Android Studio.
   - Pilih **Build → Generate Signed Bundle / APK...**.
   - Pilih **APK** (untuk distribusi link langsung) dan ulangi untuk **Android App Bundle (AAB)** (untuk Google Play Store di masa mendatang).
   - Buat/gunakan keystore `.jks` dan **simpan file keystore + password tersebut di tempat aman**.
   - **Penting:** Untuk setiap pembaruan berikutnya, **WAJIB menggunakan keystore dan `applicationId` (`com.aistudio.sociodetective.xqvrkm`) yang sama** agar pengguna bisa memperbarui aplikasi tanpa kehilangan data lokal mereka.
3. Ganti nama file release APK menjadi `SocioDetective.apk` dan salin ke `web/downloads/SocioDetective.apk`.

---

## 3. Cara Hosting di Cloudflare Pages (`https://sociodetective.pages.dev`) & Mengatasi Error 502 / Tidak Dapat Diakses

### Penyebab Umum Error `502 Bad Gateway` atau Domain Tidak Terbuka pada Cloudflare Pages
1. **Deployment belum aktif / gagal build**: Cloudflare Pages mencoba menjalankan perintah build Node/Gradle yang gagal atau proyek belum menerima unggahan aset dari folder `web/`.
2. **Output directory salah**: Default Cloudflare Pages sering mengarah ke root `/`, padahal file `download.html`, `index.html`, `version.json`, `icons/`, dan `downloads/` berada di dalam folder **`web`**.
3. **File `SocioDetective.apk` belum ada di `web/downloads/`**: Halaman `download.html` tetap terbuka, namun unduhan APK berstatus `404` sampai file release APK diunggah.

### Cara Perbaikan di Cloudflare Pages (Pilih Salah Satu Metode)

#### Metode A — Direct Upload (Paling Cepat, Tanpa Build Server)
1. Pastikan file **Signed Release APK** sudah disalin ke `web/downloads/SocioDetective.apk`.
2. Buka **Cloudflare Dashboard → Workers & Pages → proyek `sociodetective` → Create deployment (Upload assets)**.
3. Unggah isi folder **`web/`** (pastikan `download.html`, `index.html`, `_headers`, `version.json`, `manifest.webmanifest`, `sw.js`, `icons/`, `vendor/`, dan `downloads/` ikut terunggah).
4. Setelah selesai, uji langsung:
   - `https://sociodetective.pages.dev/download.html`
   - `https://sociodetective.pages.dev/index.html`
   - `https://sociodetective.pages.dev/downloads/SocioDetective.apk`

#### Metode B — Terhubung ke GitHub (Otomatis via `wrangler.toml` / GitHub Actions)
Di **Cloudflare Pages → Settings → Builds & deployments**:
- **Framework preset:** `None`
- **Build command:** *(kosongkan / jangan diisi perintah apa pun)*
- **Build output directory:** `web`
- **Root directory:** `/`

Repositori ini juga telah dilengkapi:
- `wrangler.toml` (`pages_build_output_dir = "web"`)
- `functions/api/apk-status.js` (Endpoint backend `/api/apk-status` untuk memverifikasi keberadaan file APK dan otomatis menampilkan status **"Aplikasi sedang dalam pemeliharaan"** di `download.html` jika APK belum diunggah)
- `web/_headers` (Header `application/vnd.android.package-archive`, `Cache-Control`, dan CORS `Access-Control-Allow-Origin: *` agar Android APK dapat memeriksa `version.json` & `/api/apk-status`)
- `.github/workflows/deploy-cloudflare-pages.yml` (Deployment otomatis jika `CLOUDFLARE_API_TOKEN` & `CLOUDFLARE_ACCOUNT_ID` disetel di GitHub Secrets).

### Alternatif Hosting Lainnya
- **Netlify / Vercel**: Unggah folder `web/` dengan output directory `web` dan tanpa build command.
- **GitHub Pages**: Push isi `web/` ke branch `gh-pages` atau folder `docs/`.
- **Firebase Hosting**: Jalankan `firebase deploy --only hosting` dengan `public: "web"`.

Setelah terbit di HTTPS:
- Bagikan link `https://sociodetective.pages.dev/download.html` kepada guru dan siswa untuk mengunduh APK, atau `https://sociodetective.pages.dev/` untuk langsung membuka Web/PWA.
- Di dalam aplikasi, buka **📲 Bagikan & Cek Update → ⚙️ Pengaturan URL** jika Anda ingin mengganti domain hosting kustom.

---

## 4. Cara Merilis Pembaruan Aplikasi

1. Naikkan `versionCode` (mis. `1` → `2`) dan `versionName` (mis. `"1.0"` → `"1.1"`) pada `app/build.gradle.kts` serta `APP_DISTRIBUTION_DEFAULTS` di `app/src/main/assets/sociodetective.html`.
2. Build ulang **Signed Release APK** dengan keystore yang **sama**, lalu ganti file di `downloads/SocioDetective.apk`.
3. Perbarui `web/version.json` agar `"versionCode": 2`, `"versionName": "1.1"`, dan isi `"releaseNotes"` terbaru.
4. Pengguna yang menekan **Periksa Versi Terbaru** di dalam aplikasi akan otomatis melihat pemberitahuan versi baru beserta tombol unduh APK terbaru tanpa menghapus akun maupun progres mereka.

---

## 5. Status Transparan: Backend, Database Terpusat, & Layanan Email

Saat ini:
- Aplikasi berjalan dengan **penyimpanan lokal perangkat** (`localStorage` / WebView storage) dan **SHA-256 salted password hashing**.
- Pada sesi sebelumnya, provisi **Firebase** telah ditolak oleh pengguna, dan container pengembangan ini tidak terhubung ke server database eksternal maupun layanan email SMTP.
- Akibatnya, data akun, kelas, kuis, dan progres **belum tersinkronisasi otomatis antar-perangkat berbeda** dan OTP verifikasi masih berstatus pratinjau lokal (bukan email ke inbox asli).

Agar versi Web, PWA, dan Android APK berbagi database yang sama secara real-time di perangkat berbeda, Anda perlu menyiapkan salah satu arsitektur backend berikut:
1. **Firebase (Auth + Firestore + Cloud Functions / SMTP):** Aktifkan Email/Password Auth (dengan verifikasi email sungguhan) dan Cloud Firestore untuk sinkronisasi akun, kelas, materi, kuis, dan progres antara APK dan Web/PWA.
2. **Backend API Sendiri (Node.js/Express atau Supabase/PostgreSQL + Resend/SendGrid SMTP):**
   - Sediakan REST API terautentikasi JWT (`POST /auth/register`, `POST /auth/verify-email`, `POST /auth/login`, `GET/POST /classes`, `/quizzes`, `/progress`).
   - Gunakan layanan email transaksional (Resend, SendGrid, Mailgun, atau SMTP sekolah) untuk mengirim OTP/tautan verifikasi email asli.
   - Hubungkan endpoint API tersebut dari Web/PWA dan Android APK agar seluruh siswa dan guru membaca dan menulis ke database yang sama.
