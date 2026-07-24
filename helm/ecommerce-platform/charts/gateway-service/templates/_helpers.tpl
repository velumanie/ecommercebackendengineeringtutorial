{{- define "gateway-service.fullname" -}}
gateway-service
{{- end -}}
{{- define "gateway-service.labels" -}}
app: {{ include "gateway-service.fullname" . }}
{{- end -}}
