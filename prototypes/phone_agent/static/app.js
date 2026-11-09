'use strict';
const $ = (selector) => document.querySelector(selector);
const escape = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let state, tripId, busy = false;
const labels = {AWAITING_APPROVAL:'승인 대기',READY:'발신 준비',CONFIRMED:'예약 확정',DECLINED:'예약 불가',UNKNOWN:'결과 미확인',FAILED:'연결 실패'};
const changes = {AWAITING_USER:'사용자 확인 대기',RECONFIRMING:'식당 재확인 대기',APPLIED:'변경 완료',REJECTED:'변경 거절',UNKNOWN:'변경 결과 미확인'};
const scenarios = {ACCEPT:'명확한 수락 + 필수 조건 가능',DECLINE:'예약 거절',AMBIGUOUS:'애매한 답변',DISCONNECT:'통화 중 연결 끊김',REQUIREMENT_UNKNOWN:'필수 조건 미확인',REQUIREMENT_UNAVAILABLE:'필수 조건 충족 불가',BUSY:'통화 중',TIME_MISMATCH:'다른 시간 제안',COST_REQUIRED:'추가 비용 요구',ARS:'자동 안내 응답'};
function message(value, error = false) {const el=$('#message');el.hidden=false;el.textContent=value;el.className=error?'error':'';}
async function refresh() {const response=await fetch('/api/state');if(!response.ok)throw new Error('상태를 조회하지 못했어요.');state=await response.json();render();}
async function act(action, payload = {}) {
  const response=await fetch('/api/action',{method:'POST',headers:{'Content-Type':'application/json','X-Prototype-Request':'1'},body:JSON.stringify({action,payload,requestId:crypto.randomUUID()})});
  const body=await response.json();
  if(!response.ok)throw new Error(body.error?.message || '요청을 처리하지 못했어요.');
  state=body.state;render();return body.result;
}
function run(fn) {return async (event)=>{event?.preventDefault();if(busy)return;busy=true;document.body.setAttribute('aria-busy','true');try{await fn(event);}catch(error){message(error.message,true);try{await refresh();}catch{}}finally{busy=false;document.body.removeAttribute('aria-busy');}};}
function readConditions() {const form=$('#trip-form');return {partySize:Number(form.partySize.value),highChairCount:Number(form.highChairCount.value),petAccompanied:form.petAccompanied.value,stepFree:form.stepFree.checked,dietaryRequirements:form.dietaryRequirements.value};}
function latestBooking(id) {return state.bookings.find((booking)=>booking.id===id);}
function bookingPayload(booking) {return {bookingId:booking.id,version:booking.version};}
function summary(snapshot) {if(!snapshot)return '';const parts=[`총 ${snapshot.partySize}명`,`유아의자 ${snapshot.highChairCount}개`,snapshot.petAccompanied==='YES'?'반려동물 동반':snapshot.petAccompanied==='NO'?'반려동물 비동반':'반려동물 미확인'];if(snapshot.stepFree)parts.push('계단 없이 입장');if(snapshot.dietaryRequirements)parts.push(snapshot.dietaryRequirements);return parts.join(' · ');}
function render() {
  if(!state)return;
  if(!state.trips.some((trip)=>trip.id===tripId))tripId=state.trips.at(-1)?.id;
  const trip=state.trips.find((trip)=>trip.id===tripId);
  $('#trip-status').textContent=trip?`현재 여정 v${trip.version} · ${summary(trip.conditions)}`:'저장된 여정이 없어요.';
  $('#profile-status').textContent=state.profile?'선택 저장된 프로필이 있어요. 불러온 뒤 이번 조건을 확인해 주세요.':'재사용 프로필이 저장되어 있지 않아요.';
  $('#load-profile').disabled=!state.profile;$('#delete-profile').disabled=!state.profile;$('#update-trip').disabled=!trip;
  $('#booking-count').textContent=`${state.bookings.length}건`;
  const container=$('#bookings');container.className=state.bookings.length?'':'empty';
  container.innerHTML=state.bookings.length?state.bookings.toReversed().map((booking)=>{
    let actions='';
    if(booking.status==='AWAITING_APPROVAL')actions='<button data-action="approve">이 조건으로 가상 발신 승인</button>';
    if(booking.status==='READY')actions=`<select aria-label="가상 식당 응답">${Object.entries(scenarios).map(([key,label])=>`<option value="${key}">${label}</option>`).join('')}</select><button data-action="call">가상 통화 시작</button>`;
    if(booking.status==='UNKNOWN')actions='<button data-action="verify-exists" class="outline">기존 예약·필수 조건 모두 확인됨</button><button data-action="verify-missing" class="quiet">대조 결과 예약 없음</button>';
    let change='';
    if(booking.change){const c=booking.change;let controls='';if(c.status==='AWAITING_USER')controls='<button data-action="approve-change">변경 승인</button><button data-action="reject-change" class="outline">거절</button>';if(c.status==='RECONFIRMING')controls='<button data-action="reconfirm">식당이 변경 수락</button><button data-action="reconfirm-decline" class="outline">식당 거절</button><button data-action="reconfirm-disconnect" class="quiet">재확인 중 단절</button>';change=`<div class="change"><b>${escape(changes[c.status]||c.status)}</b><p>${escape(c.originalTime)} → ${escape(c.proposedTime)} · ${escape(c.reason)}</p><div class="actions">${controls}</div></div>`;}
    return `<article class="booking" data-booking="${escape(booking.id)}"><div class="booking-head"><h3>${escape(booking.restaurantName)}</h3><span class="badge ${escape(booking.status)}">${escape(labels[booking.status]||booking.status)}</span></div><p class="details">${escape(booking.date)} · ${escape(booking.confirmedTime||booking.requestedTime)} · ${escape(booking.reservationName)}</p><div class="conditions">${escape(summary(booking.approvedSnapshot||booking.snapshot))}<br>예약 당시 조건 보존 · 예약 v${booking.version}</div><p class="notice">${escape(booking.notice)}</p><div class="actions">${actions}</div>${change}</article>`;
  }).join(''):'아직 예약이 없어요. 왼쪽에서 여정 조건과 예약을 만들어 주세요.';
  const speakers={agent:'AI 예약 비서 · 가상',restaurant:'가상 식당',system:'처리 결과'};
  $('#events').innerHTML=state.events.length?state.events.map((event)=>`<div class="event ${escape(event.speaker)}"><b>${escape(speakers[event.speaker]||event.speaker)}</b>${escape(event.message)}<time>${escape(new Date(event.at).toLocaleTimeString('ko-KR'))}</time></div>`).join(''):'<p class="muted">가상 통화를 시작하면 대화 기록이 표시됩니다.</p>';
  $('#events').scrollTop=$('#events').scrollHeight;
  const unmatched=state.callbacks.filter((callback)=>!callback.bookingId).length;
  $('#callback-status').textContent=unmatched?`예약을 특정하지 못한 회신 ${unmatched}건 · 다른 예약을 임의 변경하지 않았어요.`:'';
}
$('#trip-form').addEventListener('submit',run(async()=>{const form=$('#trip-form');const value=readConditions();const result=await act('create_trip',{conditions:value});tripId=result.id;if(form.remember.checked)await act('save_profile',{remember:true,reservationName:form.reservationName.value,conditions:value});render();message('이번 동승자 조건을 저장했어요. 예약 요청을 만들어 주세요.');}));
$('#update-trip').addEventListener('click',run(async()=>{const trip=state.trips.find((value)=>value.id===tripId);if(!trip)throw new Error('먼저 여정 조건을 저장해 주세요.');await act('update_trip',{tripId,version:trip.version,conditions:readConditions()});message('현재 여정만 수정했어요. 기존 예약 당시 조건은 그대로예요.');}));
$('#load-profile').addEventListener('click',()=>{if(!state.profile)return;const form=$('#trip-form'),profile=state.profile;form.reservationName.value=profile.reservationName;for(const [key,value] of Object.entries(profile.template)){if(!form.elements[key])continue;if(form.elements[key].type==='checkbox')form.elements[key].checked=value;else form.elements[key].value=value;}message('입력 초안만 불러왔어요. 이번 동승자 조건을 확인하고 새 여정으로 저장하세요.');});
$('#delete-profile').addEventListener('click',run(async()=>{await act('delete_profile');message('재사용 프로필을 삭제했어요. 진행 중 예약의 조건 사본은 별도로 유지돼요.');}));
$('#booking-form').addEventListener('submit',run(async()=>{const form=$('#booking-form'),trip=state.trips.find((value)=>value.id===tripId);if(!trip)throw new Error('먼저 이번 여정 조건을 저장해 주세요.');await act('create_booking',{tripId,tripVersion:trip.version,reservationName:$('#trip-form').reservationName.value,restaurantId:form.restaurantId.value,date:form.date.value,time:form.time.value});message('요청을 만들었어요. 예약 당시 조건을 확인하고 발신을 승인하세요.');}));
$('#bookings').addEventListener('click',run(async(event)=>{const button=event.target.closest('button[data-action]');if(!button)return;const card=button.closest('[data-booking]'),booking=latestBooking(card.dataset.booking),payload=bookingPayload(booking),action=button.dataset.action;
  if(action==='approve')await act('approve_booking',payload);
  if(action==='call')await act('run_call',{...payload,scenario:card.querySelector('select').value});
  if(action==='verify-exists'||action==='verify-missing')await act('verify_booking',{...payload,result:action==='verify-exists'?'EXISTS':'NOT_EXISTS',allConditionsConfirmed:true});
  if(action==='approve-change'||action==='reject-change')await act('decide_change',{...payload,approve:action==='approve-change'});
  if(action.startsWith('reconfirm'))await act('reconfirm_change',{...payload,result:action==='reconfirm'?'ACCEPT':action==='reconfirm-decline'?'DECLINE':'DISCONNECT'});
  message(latestBooking(booking.id).notice);
}));
$('#callback-form').addEventListener('submit',run(async()=>{const form=$('#callback-form');const payload=Object.fromEntries(new FormData(form));const result=await act('receive_callback',payload);message(result.matched?'예약을 찾고 변경 요청을 저장했어요. 사용자 승인을 기다립니다.':'예약을 정확히 찾지 못했어요. 다른 예약은 변경하지 않았어요.',!result.matched);}));
$('#refresh').addEventListener('click',run(async()=>{await refresh();message('로컬 DB에서 최신 상태를 불러왔어요.');}));
$('#delete-all').addEventListener('click',run(async()=>{if(!confirm('이 세션의 프로필·여정·예약·변경·대화 기록을 모두 삭제할까요?'))return;await act('delete_all',{confirm:true});tripId=null;render();message('이 세션의 모든 테스트 정보를 삭제했어요.');}));
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
