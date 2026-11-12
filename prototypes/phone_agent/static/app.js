'use strict';
const $ = (selector) => document.querySelector(selector);
const escape = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let state, tripId, busy = false, polling = false, stateVersion = 0, liveDialing = false;
let webSearching = false;
const labels = {AWAITING_APPROVAL:'승인 대기',READY:'발신 준비',CALLING:'실제 통화 중',CONFIRMED:'예약 확정',DECLINED:'예약 불가',UNKNOWN:'결과 미확인',FAILED:'연결 실패'};
const venueLabels = {RESTAURANT: '식당', CAFE: '카페', UNKNOWN: '업종 미확인'};
const businessDetailLabels = {CUISINE: '음식 종류', MENU: '메뉴', OPENING_HOURS: '영업시간', BREAK_TIME: '브레이크타임',
  LAST_ORDER: '마지막 주문', REVIEW_COUNT: '방문자 리뷰 수'};
const changes = {AWAITING_USER:'사용자 확인 대기',RECONFIRMING:'식당 재확인 대기',APPLIED:'변경 완료',REJECTED:'변경 거절',UNKNOWN:'변경 결과 미확인'};
const changeButtons = '<button data-action="approve-change">변경 승인</button><button data-action="reject-change" class="outline">거절</button>';
const scenarios = {ACCEPT:'명확한 수락 + 필수 조건 가능',DECLINE:'예약 거절',AMBIGUOUS:'애매한 답변',DISCONNECT:'통화 중 연결 끊김',REQUIREMENT_UNKNOWN:'필수 조건 미확인',REQUIREMENT_UNAVAILABLE:'필수 조건 충족 불가',BUSY:'통화 중',TIME_MISMATCH:'다른 시간 제안',COST_REQUIRED:'추가 비용 요구',ARS:'자동 안내 응답'};
function message(value, error = false) {const el=$('#message');el.hidden=false;el.textContent=value;el.className=error?'error':'';}
async function refresh() {const version=stateVersion;const response=await fetch('/api/state');if(!response.ok)throw new Error('상태를 조회하지 못했어요.');const fresh=await response.json();if(version!==stateVersion)return;state=fresh;render();}
async function act(action, payload = {}) {
  stateVersion++;
  const response=await fetch('/api/action',{method:'POST',headers:{'Content-Type':'application/json','X-Prototype-Request':'1'},body:JSON.stringify({action,payload,requestId:crypto.randomUUID()})});
  const body=await response.json();
  if(!response.ok)throw new Error(body.error?.message || '요청을 처리하지 못했어요.');
  state=body.state;render();return body.result;
}
function run(fn) {return async (event)=>{event?.preventDefault();if(busy)return;busy=true;document.body.setAttribute('aria-busy','true');try{await fn(event);}catch(error){message(error.message,true);try{await refresh();}catch{}}finally{busy=false;document.body.removeAttribute('aria-busy');}};}
function readConditions() {
  const form=$('#trip-form');
  const value = {partySize:Number(form.partySize.value),highChairCount:Number(form.highChairCount.value),
    petAccompanied:form.petAccompanied.value,stepFree:form.stepFree.checked,
    childFriendly:form.childFriendly.checked,parkingRequired:form.parkingRequired.checked,
    dietaryRequirements:form.dietaryRequirements.value};
  if (form.maxArrivalWaitMinutes.value !== '') value.maxArrivalWaitMinutes = Number(form.maxArrivalWaitMinutes.value);
  return value;
}
function latestBooking(id) {return state.bookings.find((booking)=>booking.id===id);}
function bookingPayload(booking) {return {bookingId:booking.id,version:booking.version};}
function summary(snapshot) {
  if (!snapshot) return '';
  const pets = {YES:'반려동물 동반', NO:'반려동물 비동반', UNKNOWN:'반려동물 미확인'};
  const parts = [`총 ${snapshot.partySize}명`, `유아의자 ${snapshot.highChairCount}개`, pets[snapshot.petAccompanied]];
  if (snapshot.preferredCuisines?.length) parts.push(`음식 취향: ${snapshot.preferredCuisines.join(' 또는 ')}`);
  if (snapshot.childFriendly) parts.push('아동 동반 이용');
  if (snapshot.parkingRequired) parts.push('주차 가능');
  if (snapshot.maxArrivalWaitMinutes !== undefined) {
    parts.push(`도착 시 대기 ${snapshot.maxArrivalWaitMinutes}분 이내`);
  }
  if (snapshot.stepFree) parts.push('계단 없이 입장');
  if (snapshot.dietaryRequirements) parts.push(snapshot.dietaryRequirements);
  return parts.join(' · ');
}
function publicContact(restaurant) {
  const phone = restaurant.phone ? `<br>공개 식당 번호 ${escape(restaurant.phone)} · ` : ' ';
  const category = restaurant.venueCategory ? `${venueLabels[restaurant.venueCategory] || venueLabels.UNKNOWN} · ` : '';
  return `${escape(category)}${escape(restaurant.address)}${phone}${citation(restaurant.sourceUrl, restaurant.phone ? '번호 출처' : '지점 출처')}`;
}
function renderTestRestaurants() {
  if (!state) return;
  const select = $('#booking-form').restaurantId;
  const directory = state.testRestaurants || [];
  const group = $('#test-restaurants');
  if (group.childElementCount !== directory.length) {
    const selected = group.childElementCount ? select.value : directory[0]?.id || select.value;
    group.replaceChildren(...directory.map((entry) => new Option(entry.name, entry.id)));
    group.hidden = !directory.length;
    if (Array.from(select.options).some((option) => option.value === selected)) select.value = selected;
  }
  const entry = directory.find((value) => value.id === select.value);
  const contact = $('#restaurant-contact');
  contact.hidden = !entry;
  if (entry) contact.innerHTML = publicContact(entry);
}
function renderIncomingChanges() {
  const panel = $('#incoming-changes');
  const bookings = state.bookings.filter((booking) => booking.change?.waitingOnPhone && !booking.reviewRequired);
  panel.hidden = bookings.length === 0;
  const revision = bookings.map((booking) => `${booking.id}:${booking.version}`).join(',');
  if (panel.dataset.revision === revision) return;
  panel.dataset.revision = revision;
  panel.innerHTML = bookings.length ? `<h2>식당이 통화에서 답을 기다리고 있어요</h2>${bookings.map((booking) => {
    const change = booking.change;
    return `<div data-booking="${escape(booking.id)}"><p><b>${escape(booking.restaurantName)}</b> · ${escape(booking.date)} · ${escape(change.originalTime)} → ${escape(change.proposedTime)}</p><p>${escape(change.reason)}</p><div class="actions">${changeButtons}</div></div>`;
  }).join('')}` : '';
}
function render() {
  if(!state)return;
  renderIncomingChanges();
  const webPanel = $('#web-recommendation');
  webPanel.hidden = !state.webSearch;
  if (state.webSearch && !webSearching) {
    $('#web-search-form button').disabled = !state.webSearch.configured;
    const status = $('#web-search-status');
    if (!state.webSearch.configured) status.textContent = '웹검색 서버 설정 대기';
  }
  renderTestRestaurants();
  if(!state.trips.some((trip)=>trip.id===tripId))tripId=state.trips.at(-1)?.id;
  const trip=state.trips.find((trip)=>trip.id===tripId);
  $('#trip-status').textContent=trip?`현재 여정 v${trip.version} · ${summary(trip.conditions)}`:'저장된 여정이 없어요.';
  $('#profile-status').textContent=state.profile?'선택 저장된 프로필이 있어요. 불러온 뒤 이번 조건을 확인해 주세요.':'재사용 프로필이 저장되어 있지 않아요.';
  $('#load-profile').disabled=!state.profile;$('#delete-profile').disabled=!state.profile;$('#update-trip').disabled=!trip;
  $('#booking-count').textContent=`${state.bookings.length}건`;
  const voice=state.voice||{};
  $('.mode').textContent=state.mode==='LIVE'?`LIVE · 발신 요청 ${state.externalCalls}회 · 수신 접수 ${state.inboundCalls||0}회`:'MOCK · 외부 통화 0회';
  $('#voice-status').textContent=voice.ready?'실전화 연결 설정 준비됨 · 가상 통화와 실제 발신을 구분해 실행하세요.':voice.missingRuntime?.length===0?'Telkit·LiveKit 연결 설정 완료 · 실제 통화 실행 승인 대기':`실전화 설정 대기 · ${voice.missingRuntime?.join(', ')||'설정을 확인해 주세요.'}`;
  if(voice.inboundEnabled)$('#voice-status').textContent+=' · 수신 시험 허용됨(실제 연결은 worker 실행 필요)';
  if(state.personalPhoneLinked)$('#voice-status').textContent+=' · 이 세션의 전용 AI 번호 연결됨';
  const testMode = state.testRestaurants?.length || state.webSearch?.configured;
  const testRecipientLabel = voice.testPhoneInput ? '발신 전에 입력할 본인 휴대폰'
    : voice.testRecipientLast4 ? `등록 휴대폰 끝번호 ${voice.testRecipientLast4}` : '시험 번호 미확인';
  $('#test-phone-field').hidden = !testMode || !voice.testPhoneInput;
  if (testMode) {
    const readiness = voice.ready ? '시험전화 준비됨' : voice.liveEnabled ? '음성 에이전트 연결 확인 중' : '미리보기 · 실전화 비활성';
    const echo = voice.audioHandling?.echoCancellation ? ' · 에코 제거 켜짐' : '';
    const mobile = voice.testRecipientLast4 ? ` · 등록 휴대폰 끝번호 ${voice.testRecipientLast4}` : '';
    $('#voice-status').textContent = `${readiness}${mobile}${echo}`;
  }
  const container=$('#bookings');container.className=state.bookings.length?'':'empty';
  container.innerHTML=state.bookings.length?state.bookings.toReversed().map((booking)=>{
    let actions='';
    if(booking.status==='AWAITING_APPROVAL')actions='<button data-action="approve">이 조건으로 발신 승인</button>';
    if(booking.status==='READY' && !booking.liveTest)actions=`<select aria-label="가상 식당 응답">${Object.entries(scenarios).map(([key,label])=>`<option value="${key}">${label}</option>`).join('')}</select><button data-action="call">가상 통화 시작</button>`;
    if(booking.status==='UNKNOWN' && booking.mode!=='LIVE')actions='<button data-action="verify-exists" class="outline">기존 예약·필수 조건 모두 확인됨</button><button data-action="verify-missing" class="quiet">대조 결과 예약 없음</button>';
    if(!booking.mockOnly && !booking.reviewRequired && voice.ready) {
      if (booking.liveTest && (voice.testPhoneInput || voice.testRecipientLast4) && booking.status === 'READY') {
        const buttonLabel = voice.testPhoneInput ? '입력한 휴대폰으로 시험전화' : '등록 휴대폰으로 시험전화';
        actions += `<button data-action="live-call" class="outline">${buttonLabel}</button>`;
      } else if (!booking.liveTest && !testMode && (booking.status==='READY'||(booking.mode==='LIVE'&&booking.change?.status==='RECONFIRMING'))) {
        actions += '<button data-action="live-call" class="outline">실제 테스트 전화 걸기</button>';
      }
    }
    let change='';
    if(booking.change){const c=booking.change;let controls='';if(c.status==='AWAITING_USER' && !booking.reviewRequired && !c.waitingOnPhone)controls=changeButtons;if(c.status==='RECONFIRMING' && booking.mode!=='LIVE')controls='<button data-action="reconfirm">식당이 변경 수락</button><button data-action="reconfirm-decline" class="outline">식당 거절</button><button data-action="reconfirm-disconnect" class="quiet">재확인 중 단절</button>';change=`<div class="change"><b>${escape(changes[c.status]||c.status)}</b><p>${escape(c.originalTime)} → ${escape(c.proposedTime)} · ${escape(c.reason)}</p><div class="actions">${controls}</div></div>`;}
    const categories={CANCELLATION:'취소 통보',UNAVAILABLE:'예약 불가 통보',OTHER:'식당 문의'};
    const notes=state.callbacks.filter((c)=>c.bookingId===booking.id && c.status==='NEEDS_REVIEW').map((c)=>`<div class="change"><b>${escape(categories[c.category]||'식당 회신')} · 확인 필요</b><p>${escape(c.summary)}</p><p>식당 발화: ${escape(c.evidence)}</p><p>접수 기록이며 변경·취소 완료나 사용자 확인을 뜻하지 않아요.</p></div>`).join('');
    const testContact = booking.liveTest ? `<p class="details">${publicContact(booking.liveTest)}<br>시험전화 대상 ${escape(testRecipientLabel)}</p>` : '';
    const statusLabel = booking.liveTest && booking.status === 'CONFIRMED' ? '시험 예약 확정' : labels[booking.status];
    return `<article class="booking" data-booking="${escape(booking.id)}"><div class="booking-head"><h3>${escape(booking.restaurantName)}${booking.liveTest?' · 휴대폰 시험':booking.mode==='LIVE'?' · 실제 통화':''}</h3><span class="badge ${escape(booking.reviewRequired?'UNKNOWN':booking.status)}" role="status">${escape(booking.reviewRequired?'식당 회신 · 확인 필요':statusLabel||booking.status)}</span></div>${testContact}<p class="details">${escape(booking.date)} · ${escape(booking.confirmedTime||booking.requestedTime)} · ${escape(booking.reservationName)}</p><div class="conditions">${escape(summary(booking.approvedSnapshot||booking.snapshot))}<br>예약 당시 조건 보존 · 예약 v${booking.version}</div><p class="notice">${escape(booking.notice)}</p><div class="actions">${actions}</div>${change}${notes}</article>`;
  }).join(''):'아직 예약이 없어요. 왼쪽에서 여정 조건과 예약을 만들어 주세요.';
  const speakers={agent:'AI 예약 비서 · 가상',restaurant:'가상 식당',system:'처리 결과'};
  $('#events').innerHTML=state.events.length?state.events.map((event)=>`<div class="event ${escape(event.speaker)}"><b>${escape(event.mode==='LIVE'?(event.speaker==='agent'?'AI 예약 비서 · 실제 음성':event.speaker==='restaurant'?(latestBooking(event.bookingId)?.liveTest?'시험 응답자 · 실제 음성':'식당 · 실제 음성'):'실제 통화 결과'):(speakers[event.speaker]||event.speaker))}</b>${escape(event.message)}<time>${escape(new Date(event.at).toLocaleTimeString('ko-KR'))}</time></div>`).join(''):'<p class="muted">통화를 시작하면 대화 기록이 표시됩니다.</p>';
  $('#events').scrollTop=$('#events').scrollHeight;
  const unmatched=state.callbacks.filter((callback)=>!callback.bookingId).length;
  $('#callback-status').textContent=unmatched?`예약을 특정하지 못한 회신 ${unmatched}건 · 다른 예약을 임의 변경하지 않았어요.`:'';
}

const webNeeds = {
  PARKING: '주차', HIGH_CHAIR: '유아의자', STEP_FREE_ACCESS: '계단 없는 입장',
  CHILD_FRIENDLY: '어린이 동반', PET_FRIENDLY: '반려동물 동반',
  INDOOR_PET_FRIENDLY: '실내 반려동물 동반',
};
const webVerdicts = {YES: '가능 근거 있음', NO: '불가 근거 있음', UNKNOWN: '미확인', CONFLICT: '근거 상충'};
function citation(url, label) {
  try {
    const link = new URL(url);
    if (link.protocol === 'https:' && !link.username && !link.password) {
      return `<a href="${escape(link.href)}" target="_blank" rel="noreferrer">${escape(label)}</a>`;
    }
  } catch {}
  return '';
}
function webSourceLabel(url, fallback) {
  try {
    const link = new URL(url);
    const host = link.hostname;
    if (host === 'www.data.go.kr' && link.pathname === '/data/15083033/fileData.do') return '소상공인시장진흥공단 상가정보';
    if (host === 'www.gumi.go.kr') return '구미시 맛집 안내';
    if (['pcmap.place.naver.com', 'm.place.naver.com', 'map.naver.com'].includes(host)) return '네이버 플레이스';
  } catch {}
  return fallback;
}
function renderWebCandidates(candidates, searchId) {
  $('#web-search-results').innerHTML = candidates.map((candidate) => {
    const businessDetails = Object.entries(businessDetailLabels).map(([kind, label]) => {
      const detail = candidate.businessDetails?.[kind];
      if (!detail) {
        return ['OPENING_HOURS', 'BREAK_TIME'].includes(kind) ? `<li><b>${label}</b> 미확인</li>` : '';
      }
      const source = detail.sourceType === 'MERCHANT_PAGE' ? '업체 안내 출처' : '웹 출처';
      const asOf = kind === 'REVIEW_COUNT' && detail.sourceUpdatedAt
        ? ` · ${new Intl.DateTimeFormat('ko-KR', {timeZone: 'Asia/Seoul'}).format(new Date(detail.sourceUpdatedAt))} 기준`
        : '';
      return `<li><b>${label}</b> ${escape(detail.condition)}${escape(asOf)} ${citation(detail.url, webSourceLabel(detail.url, source))}</li>`;
    }).join('');
    const assessments = candidate.assessments.map(({need, verdict, evidence}) => {
      const facts = evidence.map((item) => `<li>${escape(item.condition)} ${citation(item.url, webSourceLabel(item.url, '출처'))}</li>`);
      const tone = {YES: 'CONFIRMED', NO: 'DECLINED'}[verdict] || 'UNKNOWN';
      return `<li><b>${escape(webNeeds[need] || need)}</b> <span class="badge ${tone}">${escape(webVerdicts[verdict] || '미확인')}</span>${facts.length ? `<ul>${facts.join('')}</ul>` : ''}</li>`;
    }).join('');
    const reviews = candidate.reviews.map((review) =>
      `<li>${escape(review.condition)} ${citation(review.url, '후기 출처')}</li>`).join('');
    const confirmedNeeds = candidate.assessments.filter(({verdict}) => verdict === 'YES').length;
    const reviewLabels = {YES: '긍정 언급', NO: '주의 언급', CONFLICT: '후기 내용 상충'};
    const reviewSignals = (candidate.reviewSignals || []).map(({need, verdict, evidence}) =>
      `<li><b>${escape(webNeeds[need] || need)}</b> · ${escape(reviewLabels[verdict] || '미확인')}
        <ul>${evidence.map((item) => `<li>${escape(item.condition)} ${citation(item.url, '후기 출처')}</li>`).join('')}</ul></li>`).join('');
    const category = venueLabels[candidate.venueCategory] || venueLabels.UNKNOWN;
    const selection = candidate.venueCategory === 'RESTAURANT' ? '이 식당으로'
      : candidate.venueCategory === 'CAFE' ? '이 카페로' : '이 장소로';
    const placeLink = candidate.naverPlaceUrl ? citation(candidate.naverPlaceUrl, '네이버 플레이스에서 보기') : '';
    return `<article class="booking"><h3>${escape(candidate.name)} <span class="badge">${escape(category)}</span></h3>
      <p class="recommendation-reason">추천 ${escape(candidate.ordinal)} · 필수 조건 충족 근거 ${confirmedNeeds}/${candidate.assessments.length}개</p>
      <p class="details">${escape(candidate.address)} ${citation(candidate.identitySourceUrl, webSourceLabel(candidate.identitySourceUrl, '지점 출처'))}
        ${placeLink ? `· ${placeLink}` : ''}</p>
      <h4>매장 정보</h4><ul class="web-evidence">${businessDetails}</ul>
      <ul class="web-evidence">${assessments}</ul>
      ${reviews ? `<h4>방문 후기 요약</h4><ul class="web-evidence">${reviews}</ul>`
        : '<p class="muted">방문 후기 미수집 · 실제 리뷰가 없다는 뜻은 아니에요.</p>'}
      ${reviewSignals ? `<h4>내 조건과 관련된 후기</h4><ul class="web-evidence">${reviewSignals}</ul><p class="muted">방문자의 경험이며, 업체의 현재 정책은 별도 확인이 필요해요.</p>` : ''}
      ${searchId ? `<button type="button" class="outline" data-search-id="${escape(searchId)}"
        data-ordinal="${escape(candidate.ordinal)}">${selection} 시험 예약 만들기</button>` : ''}
    </article>`;
  }).join('');
}
$('#web-search-form').venueCategory.addEventListener('change', () => {
  $('#web-search-status').textContent = '';
  $('#web-search-results').replaceChildren();
});
function webArrivalTime() {
  const booking = $('#booking-form');
  return booking.date.value && booking.time.value ? `${booking.date.value}T${booking.time.value}` : undefined;
}
for (const field of ['date', 'time']) {
  $('#booking-form').elements[field].addEventListener('input', () => {
    if ($('#web-search-results').children.length) {
      $('#web-search-status').textContent = '예약 날짜나 시간이 바뀌었어요. 후보를 다시 검색해 주세요.';
      $('#web-search-results').replaceChildren();
    }
  });
}
$('#web-search-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (webSearching || !state?.webSearch?.configured) return;
  const form = event.currentTarget;
  const destination = form.destination.value.trim();
  const foodPreferenceText = form.foodPreferenceText.value.trim();
  const arrivalAt = webArrivalTime();
  const venueCategory = form.venueCategory.value || undefined;
  const categoryLabel = venueLabels[venueCategory] || '식당·카페';
  const conditions = readConditions();
  const status = $('#web-search-status');
  webSearching = true;
  form.querySelector('button').disabled = true;
  form.venueCategory.disabled = true;
  $('#web-search-results').replaceChildren();
  status.textContent = `${destination} 주변 ${categoryLabel} 후보와 조건별 근거를 검색하고 있어요.`;
  try {
    const response = await fetch('/api/web-recommendations', {
      method: 'POST',
      headers: {'Content-Type': 'application/json', 'X-Prototype-Request': '1'},
      body: JSON.stringify({destination, conditions, venueCategory, foodPreferenceText, arrivalAt}),
    });
    const body = await response.json();
    if (!response.ok) throw new Error(body.error?.message || '웹검색을 완료하지 못했어요.');
    if (arrivalAt !== webArrivalTime()) {
      status.textContent = '예약 날짜나 시간이 바뀌었어요. 후보를 다시 검색해 주세요.';
      return;
    }
    const result = body.result;
    const foodLabel = result?.preferredCuisines?.length ? `음식 취향: ${result.preferredCuisines.join(' 또는 ')} · ` : '';
    if (result?.webState === 'RESULTS' && result.webRecommendations.length) {
      renderWebCandidates(result.webRecommendations, body.searchId);
      const sources = new Set(result.webRecommendations.map(
        (candidate) => webSourceLabel(candidate.identitySourceUrl, '웹검색')
      ));
      const source = [...sources].join(' · ');
      status.textContent = `${destination} · ${foodLabel}${source} 후보 ${result.webRecommendations.length}곳 · 필수 조건 충족 근거를 우선하고, 조건이 비슷하면 관련 후기의 주의·긍정 언급을 반영해요. 도착 시 대기와 예약 가능 여부는 별도 확인이 필요해요.`;
    } else if (['EMPTY', 'FILTERED'].includes(result?.webState)) {
      status.textContent = `${foodLabel}선택한 종류와 조건의 근거를 갖춘 ${categoryLabel} 후보를 찾지 못했어요. 음식 취향이나 검색 지역을 바꿔 다시 찾아볼 수 있어요.`;
    } else {
      const messages = {
        BUDGET_EXHAUSTED: '이 실행의 웹검색 한도를 사용했어요.',
        HTTP_BAD_REQUEST: '도로명과 건물번호를 확인해 주세요. 등록된 상가가 없는 주소는 검색되지 않을 수 있어요.',
        HTTP_UNAUTHORIZED: '검색 서비스 인증 설정을 확인해 주세요.',
        CREDENTIAL_MISSING: 'OpenAI API 키 설정을 확인해 주세요.',
        HTTP_RATE_LIMITED: '검색 서비스의 요청 한도에 도달했어요.',
      };
      status.textContent = messages[result?.webFailureReason] || '웹검색 결과를 받을 수 없었어요.';
    }
  } catch (error) {
    status.textContent = error.message;
  } finally {
    webSearching = false;
    form.querySelector('button').disabled = false;
    form.venueCategory.disabled = false;
  }
});
$('#web-search-results').addEventListener('click', run(async (event) => {
  const button = event.target.closest('[data-search-id]');
  if (!button) return;
  const form = $('#booking-form');
  const name = $('#trip-form').reservationName;
  if (!name.reportValidity() || !form.date.reportValidity() || !form.time.reportValidity()) return;
  const result = await act('create_web_test_booking', {
    searchId: button.dataset.searchId,
    candidateOrdinal: Number(button.dataset.ordinal),
    reservationName: name.value,
    date: form.date.value,
    time: form.time.value,
  });
  message('선택한 식당의 시험 예약을 만들었어요. 조건을 확인하고 발신을 승인하세요.');
  const card = document.querySelector(`[data-booking="${CSS.escape(result.id)}"]`);
  card?.scrollIntoView({block: 'center'});
  card?.querySelector('button')?.focus({preventScroll: true});
}));
$('#trip-form').addEventListener('submit',run(async()=>{const form=$('#trip-form');const value=readConditions();const result=await act('create_trip',{conditions:value});tripId=result.id;if(form.remember.checked)await act('save_profile',{remember:true,reservationName:form.reservationName.value,conditions:value});render();message('이번 동승자 조건을 저장했어요. 예약 요청을 만들어 주세요.');}));
$('#booking-form').restaurantId.addEventListener('change', renderTestRestaurants);
$('#update-trip').addEventListener('click',run(async()=>{const trip=state.trips.find((value)=>value.id===tripId);if(!trip)throw new Error('먼저 여정 조건을 저장해 주세요.');await act('update_trip',{tripId,version:trip.version,conditions:readConditions()});message('현재 여정만 수정했어요. 기존 예약 당시 조건은 그대로예요.');}));
$('#load-profile').addEventListener('click',()=>{if(!state.profile)return;const form=$('#trip-form'),profile=state.profile;form.reservationName.value=profile.reservationName;const template={childFriendly:false,parkingRequired:false,maxArrivalWaitMinutes:'',...profile.template};for(const [key,value] of Object.entries(template)){if(!form.elements[key])continue;if(form.elements[key].type==='checkbox')form.elements[key].checked=value;else form.elements[key].value=value;}message('입력 초안만 불러왔어요. 이번 동승자 조건을 확인하고 새 여정으로 저장하세요.');});
$('#delete-profile').addEventListener('click',run(async()=>{await act('delete_profile');message('재사용 프로필을 삭제했어요. 진행 중 예약의 조건 사본은 별도로 유지돼요.');}));
$('#booking-form').addEventListener('submit',run(async()=>{const form=$('#booking-form'),trip=state.trips.find((value)=>value.id===tripId);if(!trip)throw new Error('먼저 이번 여정 조건을 저장해 주세요.');await act('create_booking',{tripId,tripVersion:trip.version,reservationName:$('#trip-form').reservationName.value,restaurantId:form.restaurantId.value,date:form.date.value,time:form.time.value});message('요청을 만들었어요. 예약 당시 조건을 확인하고 발신을 승인하세요.');}));
const onBookingAction = run(async(event)=>{const button=event.target.closest('button[data-action]');if(!button)return;const card=button.closest('[data-booking]'),booking=latestBooking(card.dataset.booking),payload=bookingPayload(booking),action=button.dataset.action;
  if(action==='live-call'){
    if (booking.liveTest) {
      let recipient = `등록 휴대폰 끝번호 ${state.voice.testRecipientLast4}`;
      if (state.voice.testPhoneInput) {
        const field = $('#test-phone-number');
        const digits = field.value.replace(/[\s()-]/g, '').replace(/^\+82/, '0');
        if (!/^010[0-9]{8}$/.test(digits)) {
          field.focus();
          throw new Error('시험전화를 받을 내 휴대폰 번호를 입력해 주세요.');
        }
        payload.testPhoneNumber = digits;
        recipient = `${digits.slice(0, 3)}-${digits.slice(3, 7)}-${digits.slice(7)}`;
      }
      if (!confirm(`받을 번호 ${recipient}\n본인이 받을 수 있는 번호인지 확인해 주세요. 시험전화 1회, 최대 ${state.voice.maxSeconds}초이며 통신료와 음성 처리 사용량이 발생할 수 있어요.`)) return;
    }
    stateVersion++;
    liveDialing = true;
    message('시험전화 연결 상태를 확인하고 있어요.');
    try {
      const response=await fetch('/api/live/dial',{method:'POST',headers:{'Content-Type':'application/json','X-Prototype-Request':'1'},body:JSON.stringify({...payload,requestId:crypto.randomUUID()})});
      const body=await response.json();if(!response.ok)throw new Error(body.error?.message||'실제 발신을 요청하지 못했어요.');
      stateVersion++;
      await refresh();
    } finally {
      liveDialing = false;
    }
  }
  if(action==='approve')await act('approve_booking',payload);
  if(action==='call')await act('run_call',{...payload,scenario:card.querySelector('select').value});
  if(action==='verify-exists'||action==='verify-missing')await act('verify_booking',{...payload,result:action==='verify-exists'?'EXISTS':'NOT_EXISTS',allConditionsConfirmed:true});
  if(action==='approve-change'||action==='reject-change')await act('decide_change',{...payload,approve:action==='approve-change'});
  if(action.startsWith('reconfirm'))await act('reconfirm_change',{...payload,result:action==='reconfirm'?'ACCEPT':action==='reconfirm-decline'?'DECLINE':'DISCONNECT'});
  message(latestBooking(booking.id).notice);
});
$('#bookings').addEventListener('click', onBookingAction);
$('#incoming-changes').addEventListener('click', onBookingAction);
$('#callback-form').addEventListener('submit',run(async()=>{const form=$('#callback-form');const payload=Object.fromEntries(new FormData(form));const result=await act('receive_callback',payload);message(result.matched?'예약을 찾고 변경 요청을 저장했어요. 사용자 승인을 기다립니다.':'예약을 정확히 찾지 못했어요. 다른 예약은 변경하지 않았어요.',!result.matched);}));
$('#refresh').addEventListener('click',run(async()=>{await refresh();message('로컬 DB에서 최신 상태를 불러왔어요.');}));
$('#delete-all').addEventListener('click',run(async()=>{if(!confirm('이 세션의 프로필·여정·예약·변경·대화 기록을 모두 삭제할까요?'))return;await act('delete_all',{confirm:true});tripId=null;$('#test-phone-number').value='';render();message('이 세션의 모든 테스트 정보를 삭제했어요.');}));
$('#quick-demo').addEventListener('click',run(async()=>{
  const day=$('#booking-form').date.value;
  const demoName=`김예약-${crypto.randomUUID().slice(0,4)}`;
  message('자동 시연 중: 여정 조건과 예약을 저장합니다…');
  const trip=await act('create_trip',{conditions:{partySize:4,highChairCount:1,petAccompanied:'NO',stepFree:true,dietaryRequirements:''}});tripId=trip.id;
  const booking=await act('create_booking',{tripId,tripVersion:1,reservationName:demoName,restaurantId:'demo-garden',date:day,time:'19:00'});
  await act('approve_booking',bookingPayload(latestBooking(booking.id)));
  await act('run_call',{...bookingPayload(latestBooking(booking.id)),scenario:'ACCEPT'});
  await act('receive_callback',{restaurantId:'demo-garden',reservationName:demoName,date:day,currentTime:'19:00',proposedTime:'19:30',reason:'가상 식당의 영업 사정'});
  await act('decide_change',{...bookingPayload(latestBooking(booking.id)),approve:true});
  await act('reconfirm_change',{...bookingPayload(latestBooking(booking.id)),result:'ACCEPT'});
  message('자동 시연 완료: 19:00 예약 → 식당 회신 → 사용자 승인 → 식당 재확인 → 19:30 확정. 재사용 프로필은 생성하지 않았어요.');
}));
const day=new Date();day.setDate(day.getDate()+7);const iso=`${day.getFullYear()}-${String(day.getMonth()+1).padStart(2,'0')}-${String(day.getDate()).padStart(2,'0')}`;
$('#booking-form').date.value=iso;$('#callback-form').date.value=iso;
refresh().catch((error)=>message(error.message,true));
async function pollState(){if((busy&&!liveDialing)||polling||document.hidden)return;polling=true;try{await refresh();}catch{}finally{polling=false;}}
setInterval(() => {
  if (liveDialing || state?.bookings.some((booking) => booking.status === 'CALLING')) pollState();
}, 1000);
setInterval(() => {if(state?.mode === 'LIVE')pollState();}, 2000);
setInterval(() => {if(state?.mode !== 'LIVE')pollState();}, 5000);
document.addEventListener('visibilitychange',()=>{if(!document.hidden)pollState();});
