package com.poppy.domain.user.service;

import com.poppy.common.auth.JwtTokenizer;
import com.poppy.common.auth.cache.OAuthOTUCache;
import com.poppy.common.auth.dto.TokenRspDto;
import com.poppy.common.exception.BusinessException;
import com.poppy.common.exception.ErrorCode;
import com.poppy.domain.reservation.service.ReservationService;
import com.poppy.domain.user.dto.response.UserPopupStoreRspDto;
import com.poppy.domain.user.dto.response.UserReservationDetailRspDto;
import com.poppy.domain.user.dto.response.UserReservationRspDto;
import com.poppy.domain.user.entity.Role;
import com.poppy.domain.user.entity.User;
import com.poppy.domain.user.repository.LoginUserProvider;
import com.poppy.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final LoginUserProvider loginUserProvider;  // 로그인 유저 확인용
    private final OAuthOTUCache oAuthOTUCache;
    private final JwtTokenizer jwtTokenizer;
    private final RedisTemplate<String, String> redisTemplate;
    private final ReservationService reservationService;

    // 로그인/회원가입
    @Transactional
    public User login(String email, String phoneNumber) {
        Optional<User> optionalUser = userRepository.findByEmail(email);
        User user;

        // 가입이 안 되어 있을 경우 회원가입
        if(optionalUser.isEmpty()) {
            // 이메일 중복 확인
            if (userRepository.findByEmail(email).isPresent())
                throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);

            user = User.builder()
                    .email(email)
                    .phoneNumber(phoneNumber)
                    .nickname(null)
                    .oauthProvider("naver")
                    .role(Role.ROLE_USER)
                    .build();

            return userRepository.save(user);
        }

        // 가입이 되어 있으면 로그인
        return optionalUser.get();
    }

    public User getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    // 회원가입 후 초기 닉네임 지정
    @Transactional
    public TokenRspDto initialNickname(String nickname, String code) {
        // 닉네임 중복 확인
        if(userRepository.existsByNickname(nickname))
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);

        long userId = oAuthOTUCache.getUserId(code);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        user.updateNickname(nickname);

        // 토큰 생성
        TokenRspDto tokenRspDto = jwtTokenizer.generateTokens(user);

        // Redis에 토큰 저장
        String redisKey = "user:" + user.getId();
        redisTemplate.opsForValue().set(redisKey, tokenRspDto.getRefreshToken(),
                jwtTokenizer.getRefreshTokenExpireTime(), TimeUnit.MINUTES);

        return tokenRspDto;
    }

    // 유저의 예약 조회
    @Transactional(readOnly = true)
    public List<UserReservationRspDto> getReservations() {
        User user = loginUserProvider.getLoggedInUser();
        return reservationService.getReservations(user.getId());
    }

    // 유저의 예약 상세 조회
    @Transactional(readOnly = true)
    public UserReservationDetailRspDto getReservationById(Long reservationId) {
        User user = loginUserProvider.getLoggedInUser();
        return reservationService.getReservationById(user.getId(), reservationId);
    }

    // 유저의 예약 취소
    @Transactional
    public void cancelUserReservation(Long reservationId) {
        // 유저 확인
        User user = loginUserProvider.getLoggedInUser();

        reservationService.cancelReservationByReservationId(user.getId(), reservationId);
    }

    // 닉네임 변경
    @Transactional
    public void updateNickname(String nickname) {
        User loginUser = loginUserProvider.getLoggedInUser();
        User user = userRepository.findById(loginUser.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // 닉네임 중복 확인
        if(userRepository.existsByNickname(nickname))
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);

        user.updateNickname(nickname);
    }

    // 로그인한 유저의 최근 본 팝업
    @Transactional(readOnly = true)
    public List<UserPopupStoreRspDto> getUserPopupStoreRecent() {
        User user = loginUserProvider.getLoggedInUser();
        return userRepository.findRecentViewedStores(user.getId(), 10);
    }

    // FCM 토큰 저장
    public void updateFcmToken(Long userId, String fcmToken, Long authenticatedUserId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (!userId.equals(authenticatedUserId))
            throw new BusinessException(ErrorCode.FCM_TOKEN_UPDATE_FORBIDDEN);

        user.updateFcmToken(fcmToken);
        userRepository.save(user);
    }

    // 회원 탈퇴
    public void deleteUser() {
        User user = loginUserProvider.getLoggedInUser();

        // 일반 유저만 탈퇴 가능
        if(!user.getRole().equals(Role.ROLE_USER))
            throw new BusinessException(ErrorCode.NOT_USER_ROLE);

        // redis에서 refresh token 삭제
        String redisKey = "user:" + user.getId();
        redisTemplate.delete(redisKey);

        // 유저 삭제
        userRepository.delete(user);
    }
}
