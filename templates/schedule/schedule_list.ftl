<div id="schedule-list" sse-swap="ScheduleListUpdate" hx-swap="outerHTML">
  <style>
    @keyframes slow-spin {
      from { transform: rotate(0deg); }
      to { transform: rotate(360deg); }
    }
    .processing-shape {
      animation: slow-spin 10s linear infinite;
    }
  </style>

  <#if error??>
    <div style="background-color: var(--md-sys-color-error-container); color: var(--md-sys-color-on-error-container); padding: 12px 16px; border-radius: 8px; font-size: 14px; margin-bottom: 16px; display: flex; align-items: center; gap: 8px;">
      <m3e-icon name="error"></m3e-icon>
      <span>${error}</span>
    </div>
  </#if>

  <#if files?has_content>
    <m3e-action-list>
      <m3e-list-action class="schedule-table-heading-action" tabindex="-1">
        <div class="schedule-table-row schedule-table-heading-row">
          <span></span>
          <m3e-heading variant="label" size="large">Имя файла</m3e-heading>
          <m3e-heading variant="label" size="large">Дата изменения</m3e-heading>
          <m3e-heading variant="label" size="large">Дата создания</m3e-heading>
        </div>
      </m3e-list-action>
      <m3e-divider inset></m3e-divider>
      <#list files as file>
        <m3e-list-action data-status="${file.status}" data-id="${file.fileId}" data-name="${file.name}">
          <div class="schedule-table-row">
            <#if file.status == "VALID">
              <m3e-shape name="sunny" style="--m3e-shape-container-color: #2dc052; --m3e-shape-size: 30px;">
                <div style="width: 100%; height: 100%; display: flex; align-items: center; justify-content: center;">
                  <m3e-icon name="check" variant="rounded" style="color: white; font-size: 20px; display: block; line-height: 1;"></m3e-icon>
                </div>
              </m3e-shape>
            <#elseif file.status == "INVALID">
              <m3e-shape name="triangle" style="--m3e-shape-container-color: #ff3b30; --m3e-shape-size: 30px;">
                <div style="width: 100%; height: 100%; display: flex; align-items: center; justify-content: center;">
                  <m3e-icon name="exclamation" variant="rounded" style="color: white; font-size: 24px; display: block; line-height: 1;"></m3e-icon>
                </div>
              </m3e-shape>
            <#elseif file.status == "PROCESSING">
              <m3e-shape class="processing-shape" name="9-sided-cookie" style="--m3e-shape-container-color: #808b9f; --m3e-shape-size: 30px;">
                <div style="width: 100%; height: 100%; display: flex; align-items: center; justify-content: center;">
                  <m3e-icon name="sync" variant="rounded" style="color: white; font-size: 20px; display: block; line-height: 1; transform: scaleX(-1);"></m3e-icon>
                </div>
              </m3e-shape>
            <#elseif file.status == "EMPTY">
              <m3e-shape name="circle" style="--m3e-shape-container-color: #b0a7a0; --m3e-shape-size: 30px;">
                <div style="width: 100%; height: 100%; display: flex; align-items: center; justify-content: center;">
                  <m3e-icon name="inventory_2" variant="rounded" style="color: white; font-size: 18px; display: block; line-height: 1;"></m3e-icon>
                </div>
              </m3e-shape>
            </#if>
            <span class="schedule-file-name">${file.name}</span>
            <span class="schedule-file-date">${file.modifiedTime}</span>
            <span class="schedule-file-date">${file.createdTime}</span>
          </div>
        </m3e-list-action>
        <#if file_has_next>
          <m3e-divider inset></m3e-divider>
        </#if>
      </#list>
    </m3e-action-list>
  <#else>
    <div style="color: var(--md-sys-color-on-surface-variant); padding: 48px; text-align: center; font-size: 16px; display: flex; flex-direction: column; align-items: center; gap: 8px;">
      <m3e-icon name="calendar_today" style="font-size: 48px; opacity: 0.5;"></m3e-icon>
      <span>Нет активных расписаний. Загрузите файл, чтобы занять свободный слот.</span>
    </div>
  </#if>
</div>
