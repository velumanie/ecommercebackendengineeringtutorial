{{- define "product-service.fullname" -}}
product-service
{{- end -}}
{{- define "product-service.labels" -}}
app: {{ include "product-service.fullname" . }}
{{- end -}}
