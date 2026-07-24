{{- define "notification-service.fullname" -}}
notification-service
{{- end -}}
{{- define "notification-service.labels" -}}
app: {{ include "notification-service.fullname" . }}
{{- end -}}
