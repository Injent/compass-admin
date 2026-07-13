<!DOCTYPE html>
<html lang="ru" style="color-scheme: light;">

<head>
    <meta charset="UTF-8">
    <title>Compass Admin</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin="">
    <link href="https://fonts.googleapis.com/css2?family=Roboto:ital,wght@0,100..900;1,100..900&amp;display=swap"
        rel="stylesheet">
    <link href="https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined:opsz,wght,FILL,GRAD@24,400,0..1,0"
        rel="stylesheet">
         <link href="https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@24,400,0..1,0"
        rel="stylesheet">
    <link rel="stylesheet" href="/static/css/index.css">
    <script type="module" src="https://cdn.jsdelivr.net/npm/@m3e/web@2.5.16/dist/all.min.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/htmx.org@2.0.10/dist/htmx.min.js"
        integrity="sha384-H5SrcfygHmAuTDZphMHqBJLc3FhssKjG7w/CeCpFReSfwBWDTKpkzPP8c+cLsK+V"
        crossorigin="anonymous"></script>
    <script src="https://cdn.jsdelivr.net/npm/htmx-ext-sse@2.2.4/dist/sse.min.js"></script>
</head>

<body>
    <m3e-theme strong-focus>
        <div class="app-layout">
            <!-- Sidebar Navigation Rail -->
            <m3e-nav-rail id="nav-rail">
                <m3e-icon-button toggle>
                    <m3e-icon name="menu"></m3e-icon>
                    <m3e-icon slot="selected" name="menu_open"></m3e-icon>
                    <m3e-nav-rail-toggle for="nav-rail"></m3e-nav-rail-toggle>
                </m3e-icon-button>
                <m3e-nav-item hx-get="/schedule" hx-target="#content-area" hx-trigger="click" active>
                    <m3e-icon slot="icon" name="calendar_today"></m3e-icon>
                    <span class="nav-item-label">Расписание</span>
                </m3e-nav-item>
            </m3e-nav-rail>

            <!-- Main Content Area -->
            <main class="app-content">
                <div id="content-area" hx-get="/schedule" hx-trigger="load" hx-swap="innerHTML">
                    <!-- Loading State placeholder -->
                    <div class="loader-wrapper">
                        <span class="loader-text">Подключение к Google Drive...</span>
                    </div>
                </div>
            </main>
        </div>
    </m3e-theme>

    <script>
        // Toggle active state for nav-items
        document.addEventListener('click', function (e) {
            const navItem = e.target.closest('m3e-nav-item');
            if (navItem) {
                const rail = navItem.closest('m3e-nav-rail');
                if (rail) {
                    rail.querySelectorAll('m3e-nav-item').forEach(item => {
                        item.removeAttribute('active');
                    });
                    navItem.setAttribute('active', '');
                }
            }
        });
    </script>
</body>

</html>
