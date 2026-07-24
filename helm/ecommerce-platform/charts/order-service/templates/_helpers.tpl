{{- define "order-service.fullname" -}}
order-service
{{- end -}}

{{- define "order-service.labels" -}}
app: {{ include "order-service.fullname" . }}
{{- end -}}
