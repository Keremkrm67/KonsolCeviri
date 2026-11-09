# Konsol Çeviri

PS5 oyunlarının İngilizce altyazılarını okuyup Türkçeye çeviren ve çeviriyi Android TV ekranında, oyunun üstünde gösteren açık kaynak uygulama.

> Resmî değildir; Sony Interactive Entertainment ile bağlantılı değildir, onun tarafından onaylanmamıştır. "PlayStation", "PS5" ve "PSN" Sony Interactive Entertainment Inc.'in markalarıdır.

## Nasıl çalışır
1. TV'deki uygulama PS5'e uzaktan oynatma (chiaki-ng) ile ikinci bir izleyici olarak bağlanır; oyun HDMI'dan her zamanki gibi oynanır.
2. Altyazı bölgesi sahneden ayıklanır ve yerel ağdan telefonun tarayıcısına gönderilir (telefona uygulama kurulmaz, TV'deki karekod okutulur).
3. Telefon yazıyı Tesseract ile okur; TV okuma hatalarını düzeltir, yarım cümleleri birleştirir.
4. Çeviri: telefonda Firefox çeviri modeli (internetsiz), MyMemory ya da kullanıcının kendi ücretsiz anahtarıyla Gemini, Groq, DeepL, Microsoft, Google Cloud, LibreTranslate.
5. Çeviri okuma sırasıyla, okunabilecek süre boyunca TV'de gösterilir.

## Derleme
    ./gradlew bundlePlayRelease     # Google Play sürümü (.aab)
    ./gradlew assembleTamRelease    # GitHub sürümü: gayriresmî ücretsiz çeviri uçları da açık
    ./gradlew testPlayDebugUnitTest # birim testleri

İmza anahtarı depoda yoktur: `keystore/playceviri.jks` dosyasını kendiniz oluşturun; şifreler `PC_STORE_PASS` / `PC_KEY_PASS` ortam değişkenlerinden okunur.

## Klasörler
- `service/` — PS5 bağlantısı, kare örnekleme, okuma ve çeviri hattı
- `vision/` — altyazı ayıklama (SubtitleIsolator), sahne karartma, değişim algılama, okuma düzeltme (OcrFixer)
- `browser/` — telefon sayfasını sunan yerel HTTP/WebSocket sunucusu
- `translate/` — çeviri motorları, model indirme, önbellek
- `overlay/` — TV üstündeki altyazı katmanı ve okuma sırası
- `assets/web/` — telefon sayfası, Tesseract, Bergamot çeviri motoru
- `docs/` — GitHub Pages: gizlilik politikası ve kullanım koşulları

## Lisans
GNU Affero General Public License v3.0 (AGPL-3.0) — bkz. `LICENSE`. Üçüncü taraf bileşenlerin lisansları: `app/src/main/assets/yasal/lisanslar.txt`.

Geliştirici: Sait Kerem Kınay · skeremkinay@gmail.com
