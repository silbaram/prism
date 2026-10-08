package io.github.silbaram.prism.admin.exception

class SavedFunnelNotFoundException : RuntimeException("이 실험의 저장된 퍼널을 찾을 수 없습니다.")
class SavedFunnelConflictException : RuntimeException("다른 사용자가 퍼널을 변경했습니다. 최신 설정을 다시 열어 확인하세요. 입력한 내용은 저장되지 않았습니다.")
