# Http Protocol

1. 주요 메서드
   - GET: 조회
   - POST: 생성, 처리
   - PUT: 수정
   - DELETE: 삭제
   - PATCH: 부분 수정
   - OPTION: 옵션

1. 상태 코드
   - 200: OK, 성공
   - 201: Created, 생성됨
   - 400: Bad Request, 요청이 잘못됨
   - 401: Unauthorized, 인증필요함
   - 403: Forbidden, 인증은 됐지만 권한이 없음
   - 404: Not Found, 요청하는 리소스가 존재하지 않음
   - 409: Conflict, 충돌(중복)
   - 500: Internal Server Error, 내부 서버 에러


1. Authentication(인증) vs Authorization(인가)
   - 인증: 사용자를 식별
   - 인가: 권한을 판별하는것