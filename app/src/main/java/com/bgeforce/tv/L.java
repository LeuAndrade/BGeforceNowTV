package com.bgeforce.tv;

import java.util.*;

/** Small UI dictionary; catalog text and remote categories use GFN's selected locale. */
public final class L {
  private static final String[] KEYS =
      ("home|library|recent|catalog|settings|search|play|back|all|genres|featured|free|loading|retry|login|loginHint|logout|connections|streaming|engine|resolution|bitrate|fps|codec|server|network|stats|microphone|microphoneHint|language|system|refresh|owned|notOwned|markOwned|removeOwned|store|cancel|more|ping|queue|unavailable|quality|account|about|native|web|auto|save|details")
          .split("\\|");
  private static final Map<String, String[]> WORDS = new HashMap<>();

  static {
    add(
        "en",
        "Home|My library|Play again|Catalog|Settings|Search games|Play now|Back|All"
            + " games|Genres|Featured|Free to play|Loading…|Try again|Sign in to GeForce NOW|Scan"
            + " the QR code with your phone, choose your provider and finish signing in. This TV"
            + " continues automatically.|Sign out|Connections|Streaming|Rendering"
            + " engine|Resolution|Bitrate|FPS target|Codec|Server|Network test|Streaming"
            + " statistics|Microphone|A connected audio input is required.|Language|System"
            + " language|Refresh|In your library|Buy this game from an official store to play.|I"
            + " own this game|Remove from library|Store|Cancel|Load more|Ping|Queue"
            + " position|Unavailable|Playback|Account|About|Native · hardware|WebView ·"
            + " compatibility|Automatic|Save|Game details");
    add(
        "pt",
        "Início|Minha biblioteca|Volte a jogar|Catálogo|Configurações|Buscar jogos|Jogar"
            + " agora|Voltar|Todos os jogos|Gêneros|Destaques|Grátis para jogar|Carregando…|Tentar"
            + " novamente|Entrar no GeForce NOW|Leia o QR Code no celular, escolha seu parceiro e"
            + " conclua o login. A TV continua automaticamente.|Sair da"
            + " conta|Conexões|Streaming|Motor de reprodução|Resolução|Bitrate|FPS"
            + " desejado|Codec|Servidor|Teste de rede|Estatísticas do streaming|Microfone|É"
            + " necessário ter uma forma de captação de áudio conectada.|Idioma|Idioma do"
            + " sistema|Atualizar|Na sua biblioteca|Este jogo precisa ser comprado em uma loja"
            + " oficial para ser jogado.|Possuo este jogo|Remover da"
            + " biblioteca|Loja|Cancelar|Carregar mais|Ping|Posição na"
            + " fila|Indisponível|Jogabilidade|Conta|Sobre|Native · hardware|WebView ·"
            + " compatibilidade|Automático|Salvar|Detalhes do jogo");
    add(
        "es",
        "Inicio|Mi biblioteca|Volver a jugar|Catálogo|Ajustes|Buscar juegos|Jugar"
            + " ahora|Volver|Todos los"
            + " juegos|Géneros|Destacados|Gratis|Cargando…|Reintentar|Iniciar sesión en GeForce"
            + " NOW|Escanea el QR con tu teléfono, elige tu proveedor e inicia sesión. La TV"
            + " continúa automáticamente.|Cerrar sesión|Conexiones|Streaming|Motor de"
            + " reproducción|Resolución|Bitrate|FPS objetivo|Códec|Servidor|Prueba de"
            + " red|Estadísticas del streaming|Micrófono|Se necesita una entrada de audio"
            + " conectada.|Idioma|Idioma del sistema|Actualizar|En tu biblioteca|Compra este juego"
            + " en una tienda oficial para jugar.|Tengo este juego|Quitar de la"
            + " biblioteca|Tienda|Cancelar|Cargar más|Ping|Posición en cola|No"
            + " disponible|Jugabilidad|Cuenta|Acerca de|Native · hardware|WebView ·"
            + " compatibilidad|Automático|Guardar|Detalles del juego");
    add(
        "fr",
        "Accueil|Ma bibliothèque|Rejouer|Catalogue|Paramètres|Rechercher|Jouer|Retour|Tous les"
            + " jeux|Genres|À la une|Gratuit|Chargement…|Réessayer|Connexion à GeForce NOW|Scannez"
            + " le QR avec votre téléphone, choisissez votre fournisseur et connectez-vous. La TV"
            + " continue automatiquement.|Déconnexion|Connexions|Streaming|Moteur"
            + " vidéo|Résolution|Débit|FPS cible|Codec|Serveur|Test"
            + " réseau|Statistiques|Microphone|Une entrée audio connectée est"
            + " nécessaire.|Langue|Langue du système|Actualiser|Dans la bibliothèque|Achetez ce jeu"
            + " dans une boutique officielle pour jouer.|Je possède ce jeu|Retirer de la"
            + " bibliothèque|Boutique|Annuler|Charger plus|Ping|Position dans la"
            + " file|Indisponible|Jouabilité|Compte|À propos|Native · matériel|WebView ·"
            + " compatibilité|Automatique|Enregistrer|Détails du jeu");
    add(
        "de",
        "Start|Meine Bibliothek|Weiterspielen|Katalog|Einstellungen|Spiele suchen|Jetzt"
            + " spielen|Zurück|Alle Spiele|Genres|Highlights|Kostenlos|Laden…|Erneut versuchen|Bei"
            + " GeForce NOW anmelden|QR-Code mit dem Handy scannen, Anbieter wählen und anmelden."
            + " Der Fernseher fährt automatisch"
            + " fort.|Abmelden|Verbindungen|Streaming|Video-Engine|Auflösung|Bitrate|Ziel-FPS|Codec|Server|Netzwerktest|Streaming-Statistik|Mikrofon|Ein"
            + " angeschlossener Audioeingang ist"
            + " erforderlich.|Sprache|Systemsprache|Aktualisieren|In deiner Bibliothek|Kaufe dieses"
            + " Spiel in einem offiziellen Shop.|Ich besitze dieses Spiel|Aus Bibliothek"
            + " entfernen|Shop|Abbrechen|Mehr laden|Ping|Warteschlangenposition|Nicht"
            + " verfügbar|Spielsteuerung|Konto|Über|Native · Hardware|WebView ·"
            + " Kompatibilität|Automatisch|Speichern|Spieldetails");
    add(
        "it",
        "Home|La mia libreria|Riprendi|Catalogo|Impostazioni|Cerca giochi|Gioca ora|Indietro|Tutti"
            + " i giochi|Generi|In evidenza|Gratis|Caricamento…|Riprova|Accedi a GeForce"
            + " NOW|Scansiona il QR dal telefono, scegli il provider e accedi. La TV continua"
            + " automaticamente.|Esci|Connessioni|Streaming|Motore video|Risoluzione|Bitrate|FPS"
            + " desiderati|Codec|Server|Test di rete|Statistiche streaming|Microfono|È necessario"
            + " un ingresso audio collegato.|Lingua|Lingua di sistema|Aggiorna|Nella tua"
            + " libreria|Acquista il gioco in uno store ufficiale.|Possiedo questo gioco|Rimuovi"
            + " dalla libreria|Store|Annulla|Carica altro|Ping|Posizione in coda|Non"
            + " disponibile|Giocabilità|Account|Informazioni|Native · hardware|WebView ·"
            + " compatibilità|Automatico|Salva|Dettagli del gioco");
    add(
        "zh",
        "首页|我的游戏库|继续游戏|游戏目录|设置|搜索游戏|立即游玩|返回|所有游戏|游戏类型|精选|免费游戏|加载中…|重试|登录 GeForce"
            + " NOW|使用手机扫描二维码，选择服务商并登录。电视将自动继续。|退出登录|连接|串流|视频引擎|分辨率|码率|目标帧率|编解码器|服务器|网络测试|串流统计|麦克风|需要连接音频输入设备。|语言|系统语言|刷新|已在游戏库中|请在官方商店购买此游戏。|我拥有此游戏|从游戏库移除|商店|取消|加载更多|延迟|排队位置|不可用|游戏设置|账户|关于|原生"
            + " · 硬件|WebView · 兼容|自动|保存|游戏详情");
    add(
        "ja",
        "ホーム|ライブラリ|続きから|カタログ|設定|ゲームを検索|今すぐプレイ|戻る|すべてのゲーム|ジャンル|注目|無料ゲーム|読み込み中…|再試行|GeForce NOW"
            + " にログイン|スマートフォンでQRコードを読み取り、プロバイダーを選んでログインしてください。テレビは自動的に続行します。|ログアウト|接続|ストリーミング|映像エンジン|解像度|ビットレート|目標FPS|コーデック|サーバー|ネットワークテスト|配信統計|マイク|音声入力機器の接続が必要です。|言語|システム言語|更新|ライブラリに登録済み|公式ストアでゲームを購入してください。|このゲームを所有|ライブラリから削除|ストア|キャンセル|さらに読み込む|Ping|待機順位|利用不可|ゲームプレイ|アカウント|このアプリについて|Native"
            + " · ハードウェア|WebView · 互換|自動|保存|ゲーム詳細");
    add(
        "ko",
        "홈|내 라이브러리|이어서 플레이|카탈로그|설정|게임 검색|지금 플레이|뒤로|모든 게임|장르|추천|무료 게임|로딩 중…|다시 시도|GeForce NOW"
            + " 로그인|휴대폰으로 QR을 스캔하고 제공업체를 선택하여 로그인하세요. TV가 자동으로 계속합니다.|로그아웃|연결|스트리밍|영상"
            + " 엔진|해상도|비트레이트|목표 FPS|코덱|서버|네트워크 테스트|스트리밍 통계|마이크|연결된 오디오 입력 장치가 필요합니다.|언어|시스템 언어|새로"
            + " 고침|라이브러리에 있음|공식 스토어에서 게임을 구매하세요.|게임 보유 중|라이브러리에서 제거|스토어|취소|더 보기|핑|대기 순서|사용 불가|게임"
            + " 플레이|계정|정보|Native · 하드웨어|WebView · 호환|자동|저장|게임 상세");
    add(
        "ru",
        "Главная|Моя библиотека|Продолжить|Каталог|Настройки|Поиск игр|Играть|Назад|Все"
            + " игры|Жанры|Рекомендуем|Бесплатно|Загрузка…|Повторить|Войти в GeForce"
            + " NOW|Отсканируйте QR-код телефоном, выберите провайдера и войдите. ТВ продолжит"
            + " автоматически.|Выйти|Подключения|Стриминг|Видеодвижок|Разрешение|Битрейт|Целевой"
            + " FPS|Кодек|Сервер|Тест сети|Статистика|Микрофон|Требуется подключённый"
            + " аудиовход.|Язык|Язык системы|Обновить|В библиотеке|Купите игру в официальном"
            + " магазине.|У меня есть эта игра|Удалить из библиотеки|Магазин|Отмена|Загрузить"
            + " ещё|Пинг|Место в очереди|Недоступно|Игра|Аккаунт|О приложении|Native ·"
            + " аппаратный|WebView · совместимость|Автоматически|Сохранить|Об игре");
    add(
        "ar",
        "الرئيسية|مكتبتي|متابعة اللعب|الكتالوج|الإعدادات|بحث عن ألعاب|العب الآن|رجوع|كل"
            + " الألعاب|الأنواع|المميزة|مجانية|جار التحميل…|إعادة المحاولة|تسجيل الدخول إلى GeForce"
            + " NOW|امسح رمز QR بهاتفك واختر مزود الخدمة وسجّل الدخول. يتابع التلفاز"
            + " تلقائيًا.|تسجيل الخروج|الاتصالات|البث|محرك الفيديو|الدقة|معدل البت|الإطارات"
            + " المستهدفة|الترميز|الخادم|اختبار الشبكة|إحصاءات البث|الميكروفون|يلزم توصيل جهاز"
            + " إدخال صوتي.|اللغة|لغة النظام|تحديث|في مكتبتك|اشتر اللعبة من متجر رسمي للعب.|أملك"
            + " هذه اللعبة|إزالة من المكتبة|المتجر|إلغاء|تحميل المزيد|زمن الاستجابة|الموضع في"
            + " الانتظار|غير متاح|اللعب|الحساب|حول|Native · عتادي|WebView · توافق|تلقائي|حفظ|تفاصيل"
            + " اللعبة");
    add(
        "hi",
        "होम|मेरी लाइब्रेरी|फिर से खेलें|कैटलॉग|सेटिंग|गेम खोजें|अभी खेलें|वापस|सभी गेम|शैलियाँ|खास"
            + " गेम|मुफ़्त गेम|लोड हो रहा है…|फिर कोशिश करें|GeForce NOW में साइन इन करें|फ़ोन से"
            + " QR स्कैन करें, प्रदाता चुनें और साइन इन करें। टीवी अपने आप आगे बढ़ेगा।|साइन"
            + " आउट|कनेक्शन|स्ट्रीमिंग|वीडियो इंजन|रिज़ॉल्यूशन|बिटरेट|लक्षित"
            + " FPS|कोडेक|सर्वर|नेटवर्क परीक्षण|स्ट्रीमिंग आँकड़े|माइक्रोफ़ोन|ऑडियो इनपुट डिवाइस"
            + " जुड़ा होना चाहिए।|भाषा|सिस्टम भाषा|रीफ़्रेश|आपकी लाइब्रेरी में|खेलने के लिए"
            + " आधिकारिक स्टोर से गेम खरीदें।|मेरे पास यह गेम है|लाइब्रेरी से हटाएँ|स्टोर|रद्द"
            + " करें|और लोड करें|पिंग|कतार में स्थान|उपलब्ध नहीं|गेमप्ले|खाता|परिचय|Native ·"
            + " हार्डवेयर|WebView · संगतता|अपने आप|सहेजें|गेम विवरण");
    add(
        "tr",
        "Ana sayfa|Kütüphanem|Tekrar oyna|Katalog|Ayarlar|Oyun ara|Şimdi oyna|Geri|Tüm"
            + " oyunlar|Türler|Öne çıkanlar|Ücretsiz|Yükleniyor…|Yeniden dene|GeForce NOW oturumu"
            + " aç|Telefonla QR kodunu tara, sağlayıcıyı seç ve giriş yap. TV otomatik devam"
            + " eder.|Çıkış yap|Bağlantılar|Yayın|Video motoru|Çözünürlük|Bit hızı|Hedef"
            + " FPS|Codec|Sunucu|Ağ testi|Yayın istatistikleri|Mikrofon|Bağlı bir ses girişi"
            + " gerekir.|Dil|Sistem dili|Yenile|Kütüphanende|Oynamak için oyunu resmî mağazadan"
            + " satın al.|Bu oyuna sahibim|Kütüphaneden kaldır|Mağaza|İptal|Daha fazla"
            + " yükle|Ping|Sıra konumu|Kullanılamıyor|Oynanış|Hesap|Hakkında|Native ·"
            + " donanım|WebView · uyumluluk|Otomatik|Kaydet|Oyun ayrıntıları");
    add(
        "id",
        "Beranda|Pustaka saya|Main lagi|Katalog|Pengaturan|Cari game|Main sekarang|Kembali|Semua"
            + " game|Genre|Unggulan|Gratis|Memuat…|Coba lagi|Masuk GeForce NOW|Pindai QR dengan"
            + " ponsel, pilih penyedia dan masuk. TV melanjutkan"
            + " otomatis.|Keluar|Koneksi|Streaming|Mesin video|Resolusi|Bitrate|Target"
            + " FPS|Codec|Server|Tes jaringan|Statistik streaming|Mikrofon|Perangkat masukan audio"
            + " harus terhubung.|Bahasa|Bahasa sistem|Segarkan|Dalam pustaka|Beli game di toko"
            + " resmi untuk bermain.|Saya memiliki game ini|Hapus dari pustaka|Toko|Batal|Muat"
            + " lagi|Ping|Posisi antrean|Tidak tersedia|Permainan|Akun|Tentang|Native · perangkat"
            + " keras|WebView · kompatibilitas|Otomatis|Simpan|Detail game");
  }

  private static void add(String lang, String values) {
    String[] a = values.split("\\|", -1);
    if (a.length != KEYS.length)
      throw new IllegalStateException(
          lang + ": " + a.length + " UI strings; expected " + KEYS.length);
    WORDS.put(lang, a);
  }

  private final String[] words;
  private final boolean portuguese;
  private static final Map<String, String[]> EXTRA = new HashMap<>();

  static {
    EXTRA.put("virtualMouse", new String[] {"TV remote mouse", "Mouse pelo controle da TV"});
    EXTRA.put(
        "virtualMouseHint",
        new String[] {
          "Arrows/stick: move · OK/A: click · Back: disable mouse",
          "Setas/analógico: mover · OK/A: clicar · Voltar: desativar mouse"
        });
    EXTRA.put("gameAudio", new String[] {"Game audio", "Áudio do jogo"});
    EXTRA.put("streamMenu", new String[] {"Streaming menu", "Menu do streaming"});
    EXTRA.put("backToStream", new String[] {"Return to the game", "Retornar ao jogo"});
    EXTRA.put("paste", new String[] {"Paste", "Colar"});
    EXTRA.put(
        "pasteLimit",
        new String[] {
          "Paste up to 4096 characters at a time.", "Cole até 4096 caracteres por vez."
        });
    EXTRA.put("leaveGame", new String[] {"Leave game", "Sair do jogo"});
    EXTRA.put(
        "keepSessionHint",
        new String[] {
          "Disconnect to return later while the server keeps the session alive, or end the game"
              + " now.",
          "Desconecte para retornar enquanto o servidor mantiver a sessão ativa, ou encerre o jogo"
              + " agora."
        });
    EXTRA.put("endSession", new String[] {"End session", "Encerrar sessão"});
    EXTRA.put(
        "disconnectSession",
        new String[] {"Disconnect and keep session", "Desconectar e manter sessão"});
    EXTRA.put("resumeSession", new String[] {"Resume session", "Retornar à sessão"});
    EXTRA.put(
        "activeSession",
        new String[] {"Active GeForce NOW session", "Sessão GeForce NOW em andamento"});
    EXTRA.put(
        "keyboardMouse", new String[] {"Physical keyboard and mouse", "Teclado e mouse físicos"});
    EXTRA.put(
        "captureMouse",
        new String[] {"Capture mouse during gameplay", "Capturar mouse durante o jogo"});
    EXTRA.put("touchInput", new String[] {"Touch input in the game", "Toques na tela do jogo"});
    EXTRA.put(
        "inputDetected", new String[] {"Keyboard/mouse detected", "Teclado/mouse detectados"});
    EXTRA.put("performance", new String[] {"Performance profile", "Perfil de desempenho"});
    EXTRA.put(
        "balanced",
        new String[] {"Default · 720p60 · H.264 · 8 Mbps", "Padrão · 720p60 · H.264 · 8 Mbps"});
    EXTRA.put("decoderPath", new String[] {"Hardware decoder", "Decodificador de hardware"});
    EXTRA.put("streamTarget", new String[] {"Session target", "Alvo da sessão"});
    EXTRA.put("streamDecoded", new String[] {"Decoded", "Decodificado"});
    EXTRA.put("compatible", new String[] {"Compatible · recommended", "Compatível · recomendado"});
    EXTRA.put(
        "fast",
        new String[] {"Custom low latency · advanced", "Baixa latência própria · avançado"});
    EXTRA.put(
        "direct",
        new String[] {"Direct MediaCodec output · advanced", "Saída direta MediaCodec · avançado"});
    EXTRA.put(
        "light",
        new String[] {"Light · 720p30 · H.264 · 4 Mbps", "Leve · 720p30 · H.264 · 4 Mbps"});
    EXTRA.put(
        "ultra",
        new String[] {
          "Extra light · 480p30 · H.264 · 3 Mbps", "Ultraleve · 480p30 · H.264 · 3 Mbps"
        });
    EXTRA.put("custom", new String[] {"Custom", "Personalizado"});
    EXTRA.put(
        "directSurface", new String[] {"Direct MediaCodec output", "Saída direta MediaCodec"});
    EXTRA.put("keyboard", new String[] {"Open keyboard", "Abrir teclado"});
    EXTRA.put("exitStream", new String[] {"Exit streaming", "Sair do streaming"});
    EXTRA.put("sendText", new String[] {"Send text", "Enviar texto"});
    EXTRA.put("streamChoices", new String[] {"What would you like to do?", "O que deseja fazer?"});
    EXTRA.put(
        "keyboardHint",
        new String[] {
          "Select a field in the game first. Text is sent to that field.",
          "Selecione primeiro um campo no jogo. O texto será enviado para esse campo."
        });
  }

  private final Locale locale;

  public L(Locale locale) {
    this.locale = locale;
    portuguese = locale.getLanguage().equals("pt");
    words =
        WORDS.containsKey(locale.getLanguage()) ? WORDS.get(locale.getLanguage()) : WORDS.get("en");
  }

  public String nav(String key) {
    if (key.equals("library"))
      return locale.getLanguage().equals("pt")
          ? "Biblioteca"
          : locale.getLanguage().equals("es")
              ? "Biblioteca"
              : locale.getLanguage().equals("en") ? "Library" : t(key);
    if (key.equals("settings"))
      return locale.getLanguage().equals("pt") || locale.getLanguage().equals("es")
          ? "Ajustes"
          : t(key);
    return t(key);
  }

  public String t(String key) {
    String[] added = null;
    switch (key) {
      case "fullhd":
        added = new String[] {"Full HD · 1080p60", "Full HD · 1080p60", "Full HD · 1080p60"};
        break;
      case "fullhdUnavailable":
        added =
            new String[] {
              "A TV não informou suporte a H.264 por hardware em 1920×1080 a 60 FPS. O perfil atual"
                  + " foi mantido.",
              "This TV does not report hardware H.264 at 1920×1080/60 FPS. Your current profile was"
                  + " kept.",
              "La TV no informa H.264 por hardware a 1920×1080/60 FPS. Se mantuvo el perfil actual."
            };
        break;
      case "closeSession":
        added = new String[] {"Fechar sessão", "Close session", "Cerrar sesión de juego"};
        break;
      case "closingSession":
        added = new String[] {"Encerrando sessão…", "Closing session…", "Cerrando sesión…"};
        break;
      case "closeSessionHint":
        added =
            new String[] {
              "Encerrar esta sessão na nuvem? O progresso ainda não salvo pode ser perdido.",
              "End this cloud session? Unsaved progress may be lost.",
              "¿Terminar esta sesión? El progreso sin guardar puede perderse."
            };
        break;
      case "sessionClosed":
        added = new String[] {"Sessão encerrada", "Session closed", "Sesión finalizada"};
        break;
      case "settingsHint":
        added =
            new String[] {
              "Seu jeito de jogar. Ajuste imagem, controles e conexões.",
              "Your way to play. Tune picture, controls and connections.",
              "Tu forma de jugar. Ajusta imagen, controles y conexiones."
            };
        break;
    }
    if (added != null)
      return added[
          locale.getLanguage().equals("pt") ? 0 : locale.getLanguage().equals("es") ? 2 : 1];
    for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return words[i];
    String[] extra = EXTRA.get(key);
    return extra == null ? key : extra[portuguese ? 1 : 0];
  }
}
