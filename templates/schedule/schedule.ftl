<div class="schedule-header">
  <m3e-search-bar id="file-search-bar" clearable>
    <m3e-icon name="search" slot="leading"></m3e-icon>
    <input id="search-input" slot="input" placeholder="Поиск файлов..." />
  </m3e-search-bar>

  <div class="actions-wrapper">
    <m3e-split-button>
      <m3e-button slot="leading-button" id="upload-btn">
        <m3e-icon slot="icon" name="upload"></m3e-icon>
        Загрузить
      </m3e-button>
      <m3e-icon-button slot="trailing-button">
        <m3e-icon name="keyboard_arrow_down"></m3e-icon>
        <m3e-menu-trigger for="menu"></m3e-menu-trigger>
      </m3e-icon-button>
    </m3e-split-button>

    <m3e-menu id="menu" position-x="before">
      <m3e-menu-item>Скачать все</m3e-menu-item>
      <m3e-menu-item>Удалить</m3e-menu-item>
    </m3e-menu>
  </div>
</div>

<input type="file" id="file-input" name="files" accept=".xlsx,.xls" multiple style="display: none;"
       hx-post="/schedule/upload"
       hx-trigger="change"
       hx-target="#schedule-list"
       hx-swap="outerHTML"
       hx-encoding="multipart/form-data">

<m3e-content-pane class="schedule-pane">
  <div id="schedule-list-container" hx-ext="sse" sse-connect="/schedule/list/sse">
    <m3e-filter-chip-set id="status-filter-chips" aria-label="Фильтр по статусу" style="margin-bottom: 16px;">
      <m3e-filter-chip id="chip-all" selected data-filter="all">Все</m3e-filter-chip>
      <m3e-filter-chip id="chip-ready" data-filter="valid">Проверенные</m3e-filter-chip>
      <m3e-filter-chip id="chip-error" data-filter="invalid">С ошибками</m3e-filter-chip>
    </m3e-filter-chip-set>
    <#include "/schedule/schedule_list.ftl">
  </div>
</m3e-content-pane>

<script>
  (function() {
    document.addEventListener('click', function(e) {
      const uploadBtn = e.target.closest('#upload-btn');
      if (uploadBtn) {
        document.getElementById('file-input').click();
      }
    });

    document.addEventListener('click', function(e) {
      const listAction = e.target.closest('m3e-list-action');
      if (listAction) {
        const fileId = listAction.getAttribute('data-id');
        if (fileId) {
          window.open('/schedule/editor/' + fileId, '_blank');
        }
      }
    });

    const filterFiles = () => {
      const searchInput = document.getElementById('search-input');
      if (!searchInput) return;

      const query = searchInput.value.toLowerCase().trim();
      const selectedChip = document.querySelector('#status-filter-chips m3e-filter-chip[selected]');
      const selectedFilter = selectedChip ? selectedChip.getAttribute('data-filter') : 'all';

      const container = document.querySelector('m3e-action-list');
      if (!container) return;

      const children = Array.from(container.children);
      let lastVisibleAction = null;

      children.forEach(child => {
        const tag = child.tagName.toLowerCase();
        if (tag === 'm3e-list-action') {
          const text = child.textContent.toLowerCase();
          const status = child.getAttribute('data-status') || '';

          const matchesSearch = text.includes(query);
          const matchesFilter = (
            selectedFilter === 'all' ||
            (selectedFilter === 'valid' && status === 'VALID') ||
            (selectedFilter === 'invalid' && status === 'INVALID')
          );

          if (matchesSearch && matchesFilter) {
            child.style.display = '';
            if (lastVisibleAction) {
              let sibling = lastVisibleAction.nextElementSibling;
              while (sibling && sibling !== child) {
                if (sibling.tagName.toLowerCase() === 'm3e-divider') {
                  sibling.style.display = '';
                }
                sibling = sibling.nextElementSibling;
              }
            }
            lastVisibleAction = child;
          } else {
            child.style.display = 'none';
          }
        } else if (tag === 'm3e-divider') {
          child.style.display = 'none';
        }
      });
    };

    document.addEventListener('input', function(e) {
      if (e.target.id === 'search-input') {
        filterFiles();
      }
    });

    document.addEventListener('clear', function(e) {
      const searchBar = e.target.closest('#file-search-bar');
      if (searchBar) {
        const searchInput = document.getElementById('search-input');
        if (searchInput) searchInput.value = '';
        filterFiles();
      }
    });

    document.addEventListener('click', function(e) {
      const chip = e.target.closest('m3e-filter-chip');
      if (chip) {
        const chipSet = chip.closest('#status-filter-chips');
        if (chipSet) {
          const chips = chipSet.querySelectorAll('m3e-filter-chip');
          chips.forEach(c => {
            if (c === chip) {
              c.setAttribute('selected', '');
            } else {
              c.removeAttribute('selected');
            }
          });
          filterFiles();
        }
      }
    });

    let processingInterval = null;
    const processingShapesList = ["9-sided-cookie", "6-sided-cookie", "4-sided-cookie", "pill", "circle"];
    let processingShapeIndex = 0;

    const startProcessingAnimation = () => {
      if (processingInterval) {
        clearInterval(processingInterval);
      }

      processingInterval = setInterval(() => {
        const shapes = document.querySelectorAll('.processing-shape');
        if (shapes.length === 0) {
          clearInterval(processingInterval);
          processingInterval = null;
          return;
        }

        processingShapeIndex = (processingShapeIndex + 1) % processingShapesList.length;
        const nextShapeName = processingShapesList[processingShapeIndex];

        shapes.forEach(shape => {
          shape.setAttribute('name', nextShapeName);
        });
      }, 700);
    };

    startProcessingAnimation();

    document.addEventListener('htmx:afterSwap', function(e) {
      if (e.detail.target.id === 'schedule-list-container' || e.detail.target.id === 'schedule-list') {
        filterFiles();
        startProcessingAnimation();
      }
    });
  })();
</script>
