# Direktori Unduhan APK SocioDetective

Letakkan file **Signed Release APK** dengan nama persis:

`SocioDetective.apk`

di dalam folder `web/downloads/` sebelum mengunggah atau men-deploy folder `web/` ke Cloudflare Pages (`https://sociodetective.pages.dev`).

Dengan demikian, file APK dapat diunduh langsung melalui:
`https://sociodetective.pages.dev/downloads/SocioDetective.apk`

Catatan: Jangan mengganti file release dengan file debug atau file placeholder palsu. Pastikan `SocioDetective.apk` ditandatangani dengan keystore release yang konsisten agar pembaruan aplikasi berikutnya tidak menghapus data lokal pengguna.
