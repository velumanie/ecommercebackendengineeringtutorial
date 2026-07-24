{{- define "user-service.fullname" -}}
user-service
{{- end -}}
{{- define "user-service.labels" -}}
app: {{ include "user-service.fullname" . }}
{{- end -}}
