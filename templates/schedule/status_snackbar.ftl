<div class="m3-snackbar no-anim <#if file.status == 'VALID'>success<#elseif file.status == 'INVALID'>error</#if>">
  <div class="m3-snackbar-text">${file.statusText}</div>
</div>
