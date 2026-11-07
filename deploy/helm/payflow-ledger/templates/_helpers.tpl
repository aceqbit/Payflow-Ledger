{{- define "payflow.name" -}}payflow-ledger{{- end -}}
{{- define "payflow.secretName" -}}
{{- if .Values.secret.existingSecret }}{{ .Values.secret.existingSecret }}{{ else }}{{ include "payflow.name" . }}{{ end -}}
{{- end -}}
