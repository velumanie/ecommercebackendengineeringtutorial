{{- define "inventory-service.fullname" -}}
inventory-service
{{- end -}}
{{- define "inventory-service.labels" -}}
app: {{ include "inventory-service.fullname" . }}
{{- end -}}
