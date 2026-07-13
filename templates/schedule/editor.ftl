<!DOCTYPE html>
<html>
<head>
  <title>${file.name} - Редактор</title>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link href="https://fonts.googleapis.com/css2?family=Roboto:wght@400;500;700&display=swap" rel="stylesheet">
  <link rel="stylesheet" href="/static/css/index.css">
  <script src="https://cdn.jsdelivr.net/npm/htmx.org@2.0.10/dist/htmx.min.js"></script>
  <script src="https://cdn.jsdelivr.net/npm/htmx-ext-sse@2.2.4/dist/sse.min.js"></script>
  <style>
    body, html {
      margin: 0;
      padding: 0;
      width: 100%;
      height: 100%;
      overflow: hidden;
      background-color: var(--md-sys-color-background, #f9f0ef);
      font-family: 'Roboto', sans-serif;
    }
    .w-full { width: 100%; }
    .h-full { height: 100%; }
    .border-none { border: none; }
    .block { display: block; }
  </style>
</head>
<body>

  <iframe
    src="https://docs.google.com/spreadsheets/d/${file.fileId}/edit"
    class="w-full h-full border-none block"
    allow="autoplay">
  </iframe>

  <div class="m3-snackbar-container"
       hx-ext="sse"
       sse-connect="/schedule/status/sse/${file.fileId}">
    <div id="editor-snackbar" sse-swap="StatusUpdate" hx-swap="innerHTML">
      <#include "/schedule/status_snackbar.ftl">
    </div>
  </div>

</body>
</html>
