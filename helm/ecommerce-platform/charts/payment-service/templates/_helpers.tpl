{{- define "payment-service.fullname" -}}
payment-service
{{- end -}}
{{- define "payment-service.labels" -}}
app: {{ include "payment-service.fullname" . }}
{{- end -}}
