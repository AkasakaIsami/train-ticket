package rebook.service;

import edu.fudan.common.util.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import rebook.entity.*;
import rebook.entity.RebookInfo;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;

@Service
public class RebookServiceImpl implements RebookService {

    @Autowired
    private RestTemplate restTemplate;

    @Override
    public Response rebook(RebookInfo info, HttpHeaders httpHeaders) {

        Response queryOrderResult = getOrderByRebookInfo(info, httpHeaders);

        if (queryOrderResult.getStatus() == 1) {
            return new Response<>(0, queryOrderResult.getMsg(), null);
        }

        Order order = (Order) queryOrderResult.getData();
        int status = order.getStatus();
        if (status == OrderStatus.NOTPAID.getCode()) {
            return new Response<>(0, "You haven't paid the original ticket!", null);
        } else if (status == OrderStatus.PAID.getCode()) {
            // do nothing
        } else if (status == OrderStatus.CHANGE.getCode()) {
            return new Response<>(0, "You have already changed your ticket and you can only change one time.", null);
        } else if (status == OrderStatus.COLLECTED.getCode()) {
            return new Response<>(0, "You have already collected your ticket and you can change it now.", null);
        } else {
            return new Response<>(0, "You can't change your ticket.", null);
        }

        //查询当前时间和旧订单乘车时间，根据时间来判断能否改签，发车两小时后不能改签
        if (!checkTime(order.getTravelDate(), order.getTravelTime())) {
            return new Response<>(0, "You can only change the ticket before the train start or within 2 hours after the train start.", null);
        }

        //改签不能更换出发地和目的地，只能更改车次、席位、时间
        //查询座位余票信息和车次的详情
        TripAllDetailInfo gtdi = new TripAllDetailInfo();
        gtdi.setFrom(queryForStationName(order.getFrom(), httpHeaders));
        gtdi.setTo(queryForStationName(order.getTo(), httpHeaders));
        gtdi.setTravelDate(info.getDate());
        gtdi.setTripId(info.getTripId());
        Response gtdr = getTripAllDetailInformation(gtdi, info.getTripId(), httpHeaders);
        if (gtdr.getStatus() == 0) {
            return new Response<>(0, gtdr.getMsg(), null);
        } else {
            TripResponse tripResponse = ((TripAllDetail) gtdr.getData()).getTripResponse();
            if (info.getSeatType() == SeatClass.FIRSTCLASS.getCode()) {
                if (tripResponse.getConfortClass() <= 0) {
                    return new Response<>(0, "Seat Not Enough", null);
                }
            } else {
                if (tripResponse.getEconomyClass() == SeatClass.SECONDCLASS.getCode()) {
                    if (tripResponse.getConfortClass() <= 0) {
                        return new Response<>(0, "Seat Not Enough", null);
                    }
                }
            }
        }
        // Trip trip = ((TripAllDetail) gtdr.getData()).getTrip();

        //处理差价，多退少补
        //退掉原有的票，让其他人可以订到对应的位置

        String ticketPrice = "0";
        if (info.getSeatType() == SeatClass.FIRSTCLASS.getCode()) {
            ticketPrice = ((TripAllDetail) gtdr.getData()).getTripResponse().getPriceForConfortClass();
        } else if (info.getSeatType() == SeatClass.SECONDCLASS.getCode()) {
            ticketPrice = ((TripAllDetail) gtdr.getData()).getTripResponse().getPriceForEconomyClass();
        }
        String oldPrice = order.getPrice();
        BigDecimal priceOld = new BigDecimal(oldPrice);
        BigDecimal priceNew = new BigDecimal(ticketPrice);
        if (priceOld.compareTo(priceNew) > 0) {
            //退差价
            String difference = priceOld.subtract(priceNew).toString();
            if (!drawBackMoney(info.getLoginId(), difference, httpHeaders)) {
                return new Response<>(0, "Can't draw back the difference money, please try again!", null);
            }
            return updateOrder(order, info, (TripAllDetail) gtdr.getData(), ticketPrice, httpHeaders);

        } else if (priceOld.compareTo(priceNew) == 0) {
            //do nothing
            return updateOrder(order, info, (TripAllDetail) gtdr.getData(), ticketPrice, httpHeaders);
        } else {
            //补差价
            String difference = priceNew.subtract(priceOld).toString();
            Order orderMoneyDifference = new Order();
            orderMoneyDifference.setDifferenceMoney(difference);
            return new Response<>(1, "Please pay the different money!", orderMoneyDifference);
        }
    }

    @Override
    public Response payDifference(RebookInfo info, HttpHeaders httpHeaders) {

        Response queryOrderResult = getOrderByRebookInfo(info, httpHeaders);

        if ( queryOrderResult.getStatus() ==0) {
            return new Response<>(0, queryOrderResult.getMsg(), null);
        }
        Order order = (Order) queryOrderResult.getData();

        TripAllDetailInfo gtdi = new TripAllDetailInfo();
        gtdi.setFrom(queryForStationName(order.getFrom(), httpHeaders));
        gtdi.setTo(queryForStationName(order.getTo(), httpHeaders));
        gtdi.setTravelDate(info.getDate());
        gtdi.setTripId(info.getTripId());
        // TripAllDetail
        Response gtdrResposne = getTripAllDetailInformation(gtdi, info.getTripId(), httpHeaders);
        TripAllDetail gtdr = (TripAllDetail) gtdrResposne.getData();

        String ticketPrice = "0";
        if (info.getSeatType() == SeatClass.FIRSTCLASS.getCode()) {
            ticketPrice = gtdr.getTripResponse().getPriceForConfortClass();
        } else if (info.getSeatType() == SeatClass.SECONDCLASS.getCode()) {
            ticketPrice = gtdr.getTripResponse().getPriceForEconomyClass();
        }
        String oldPrice = order.getPrice();
        BigDecimal priceOld = new BigDecimal(oldPrice);
        BigDecimal priceNew = new BigDecimal(ticketPrice);

        if (payDifferentMoney(info.getOrderId(), info.getTripId(), info.getLoginId(), priceNew.subtract(priceOld).toString(), httpHeaders)) {
            return updateOrder(order, info, gtdr, ticketPrice, httpHeaders);
        } else {
            return new Response<>(0, "Can't pay the difference,please try again", null);
        }
    }

    private Response updateOrder(Order order, RebookInfo info, TripAllDetail gtdr, String ticketPrice, HttpHeaders httpHeaders) {

        //4.修改原有订单 设置order的各个信息
        Trip trip = gtdr.getTrip();
        String oldTripId = order.getTrainNumber();
        order.setTrainNumber(info.getTripId());
        order.setBoughtDate(new Date());
        order.setStatus(OrderStatus.CHANGE.getCode());
        order.setPrice(ticketPrice);//Set ticket price
        order.setSeatClass(info.getSeatType());
        order.setTravelDate(info.getDate());
        order.setTravelTime(trip.getStartingTime());

        if (info.getSeatType() == SeatClass.FIRSTCLASS.getCode()) {//Dispatch the seat
            Ticket ticket =
                    dipatchSeat(info.getDate(),
                            order.getTrainNumber(), order.getFrom(), order.getTo(),
                            SeatClass.FIRSTCLASS.getCode(), httpHeaders);
            order.setSeatClass(SeatClass.FIRSTCLASS.getCode());
            order.setSeatNumber("" + ticket.getSeatNo());
        } else {
            Ticket ticket =
                    dipatchSeat(info.getDate(),
                            order.getTrainNumber(), order.getFrom(), order.getTo(),
                            SeatClass.SECONDCLASS.getCode(), httpHeaders);
            order.setSeatClass(SeatClass.SECONDCLASS.getCode());
            order.setSeatNumber("" + ticket.getSeatNo());
        }

        //更新订单信息
        //原订单和新订单如果分别位于高铁动车和其他订单，应该删掉原订单，在另一边新建，用新的id
        if ((tripGD(oldTripId) && tripGD(info.getTripId())) || (!tripGD(oldTripId) && !tripGD(info.getTripId()))) {

            Response changeOrderResult = updateOrder(order, info.getTripId(), httpHeaders);
            if (changeOrderResult.getStatus() == 1) {
                return new Response<>(1, "Success!", order);
            } else {
                return new Response<>(0, "Can't update Order!", null);
            }
        } else {
            //删掉原有订单
            deleteOrder(order.getId().toString(), oldTripId, httpHeaders);
            //在另一边创建新订单
            createOrder(order, order.getTrainNumber(), httpHeaders);
            return new Response<>(1, "Success", order);
        }
    }

    public Ticket dipatchSeat(Date date, String tripId, String startStationId, String endStataionId, int seatType, HttpHeaders httpHeaders) {
        Seat seatRequest = new Seat();
        seatRequest.setTravelDate(date);
        seatRequest.setTrainNumber(tripId);
        seatRequest.setStartStation(startStationId);
        seatRequest.setSeatType(seatType);
        seatRequest.setDestStation(endStataionId);

        HttpEntity requestEntityTicket = new HttpEntity(seatRequest, httpHeaders);
        ResponseEntity<Response> reTicket = restTemplate.exchange(
                "http://ts-seat-service:18898/api/v1/seatservice/seats",
                HttpMethod.POST,
                requestEntityTicket,
                Response.class);
        Ticket ticket = (Ticket) reTicket.getBody().getData();

//        Ticket ticket = restTemplate.postForObject(
//                "http://ts-seat-service:18898/seat/getSeat"
//                ,seatRequest,Ticket.class);
        return ticket;
    }


    private boolean tripGD(String tripId) {
        if (tripId.startsWith("G") || tripId.startsWith("D")) {
            return true;
        } else {
            return false;
        }
    }

    private VerifyResult verifySsoLogin(String loginToken, HttpHeaders headers) {
        System.out.println("[Order Service][Verify Login] Verifying....");

        HttpEntity requestTokenResult = new HttpEntity(null, headers);
        ResponseEntity<VerifyResult> reTokenResult = restTemplate.exchange(
                "http://ts-sso-service:12349/verifyLoginToken/" + loginToken,
                HttpMethod.GET,
                requestTokenResult,
                VerifyResult.class);
        VerifyResult tokenResult = reTokenResult.getBody();
//        VerifyResult tokenResult = restTemplate.getForObject(
//                "http://ts-sso-service:12349/verifyLoginToken/" + loginToken,
//                VerifyResult.class);


        return tokenResult;
    }

    private boolean checkTime(Date travelDate, Date travelTime) {
        boolean result = true;
        Calendar calDateA = Calendar.getInstance();
        Date today = new Date();
        calDateA.setTime(today);
        Calendar calDateB = Calendar.getInstance();
        calDateB.setTime(travelDate);
        Calendar calDateC = Calendar.getInstance();
        calDateC.setTime(travelTime);
        if (calDateA.get(Calendar.YEAR) > calDateB.get(Calendar.YEAR)) {
            result = false;
        } else if (calDateA.get(Calendar.YEAR) == calDateB.get(Calendar.YEAR)) {
            if (calDateA.get(Calendar.MONTH) > calDateB.get(Calendar.MONTH)) {
                result = false;
            } else if (calDateA.get(Calendar.MONTH) == calDateB.get(Calendar.MONTH)) {
                if (calDateA.get(Calendar.DAY_OF_MONTH) > calDateB.get(Calendar.DAY_OF_MONTH)) {
                    result = false;
                } else if (calDateA.get(Calendar.DAY_OF_MONTH) == calDateB.get(Calendar.DAY_OF_MONTH)) {
                    if (calDateA.get(Calendar.HOUR_OF_DAY) > calDateC.get(Calendar.HOUR_OF_DAY) + 2) {
                        result = false;
                    } else if (calDateA.get(Calendar.HOUR_OF_DAY) == calDateC.get(Calendar.HOUR_OF_DAY) + 2) {
                        if (calDateA.get(Calendar.MINUTE) > calDateC.get(Calendar.MINUTE)) {
                            result = false;
                        }
                    }
                }
            }
        }
        return result;
    }


    private Response getTripAllDetailInformation(TripAllDetailInfo gtdi, String tripId, HttpHeaders httpHeaders) {
        Response gtdr;
        String requestUrl = "";
        if (tripId.startsWith("G") || tripId.startsWith("D")) {
            requestUrl = "http://ts-travel-service:12346/api/v1/travelservice/trip_detail";
            // ts-travel-service:12346/travel/getTripAllDetailInfo
        } else {
            requestUrl = "http://ts-travel2-service:16346/api/v1/travel2service/trip_detail";
            //ts-travel2-service:16346/travel2/getTripAllDetailInfo
        }
        HttpEntity requestGetTripAllDetailResult = new HttpEntity(gtdi, httpHeaders);
        ResponseEntity<Response> reGetTripAllDetailResult = restTemplate.exchange(
                requestUrl,
                HttpMethod.POST,
                requestGetTripAllDetailResult,
                Response.class);
        gtdr = reGetTripAllDetailResult.getBody();
        return gtdr;
    }

    private Response createOrder(Order order, String tripId, HttpHeaders httpHeaders) {
        String requestUrl = "";
        if (tripId.startsWith("G") || tripId.startsWith("D")) {
            // ts-order-service:12031/order/create
            requestUrl = "http://ts-order-service:12031/api/v1/orderservice/order";
        } else {
            //ts-order-other-service:12032/orderOther/create
            requestUrl = "http://ts-order-other-service:12032/api/v1/orderOtherService/orderOther";
        }
        HttpEntity requestCreateOrder = new HttpEntity(order, httpHeaders);
        ResponseEntity<Response> reCreateOrder = restTemplate.exchange(
                requestUrl,
                HttpMethod.POST,
                requestCreateOrder,
                Response.class);
        return reCreateOrder.getBody();
    }

    private Response updateOrder(Order info, String tripId, HttpHeaders httpHeaders) {
        String requestOrderUtl = "";
        if (tripGD(tripId)) {
            requestOrderUtl = "http://ts-order-service:12031/api/v1/orderservice/order";
//            result = restTemplate.postForObject("http://ts-order-service:12031/order/update",
//                    info,ChangeOrderResult.class);
        } else {
            requestOrderUtl = "http://ts-order-other-service:12032/api/v1/orderOtherService/orderOther";
//            result = restTemplate.postForObject("http://ts-order-other-service:12032/orderOther/update",
//                    info,ChangeOrderResult.class);
        }
        HttpEntity requestUpdateOrder = new HttpEntity(info, httpHeaders);
        ResponseEntity<Response> reUpdateOrder = restTemplate.exchange(
                requestOrderUtl,
                HttpMethod.PUT,
                requestUpdateOrder,
                Response.class);
        return reUpdateOrder.getBody();
    }

    private Response deleteOrder(String orderId, String tripId, HttpHeaders httpHeaders) {

        String requestUrl = "";
        if (tripGD(tripId)) {
            // http://ts-order-service:12031/order/delete
            requestUrl = "http://ts-order-service:12031/api/v1/orderservice/order/" + orderId;
        } else {
            //ts-order-other-service:12032/orderOther/delete
            requestUrl = "http://ts-order-other-service:12032/api/v1/orderOtherService/orderOther/" + orderId;
        }
        HttpEntity requestDeleteOrder = new HttpEntity(httpHeaders);
        ResponseEntity<Response> reDeleteOrder = restTemplate.exchange(
                requestUrl,
                HttpMethod.POST,
                requestDeleteOrder,
                Response.class);

        return reDeleteOrder.getBody();
    }

    private Response getOrderByRebookInfo(RebookInfo info, HttpHeaders httpHeaders) {
        Response queryOrderResult;
        //改签只能改签一次，查询订单状态来判断是否已经改签过
        String requestUrl = "";
        if (info.getOldTripId().startsWith("G") || info.getOldTripId().startsWith("D")) {
            requestUrl = "http://ts-order-service:12031/api/v1/orderservice/order/" + info.getOrderId();
            //ts-order-service:12031/order/getById
        } else {
            requestUrl = "http://ts-order-other-service:12032/api/v1/orderOtherService/orderOther/" + info.getOrderId();
            //ts-order-other-service:12032/orderOther/getById
        }
        HttpEntity requestEntityGetOrderByRebookInfo = new HttpEntity(httpHeaders);
        ResponseEntity<Response> reGetOrderByRebookInfo = restTemplate.exchange(
                requestUrl,
                HttpMethod.GET,
                requestEntityGetOrderByRebookInfo,
                Response.class);
        queryOrderResult = reGetOrderByRebookInfo.getBody();
        return queryOrderResult;
    }

    private String queryForStationName(String stationId, HttpHeaders httpHeaders) {
        HttpEntity requestEntityQueryForStationName = new HttpEntity(httpHeaders);
        ResponseEntity<Response> reQueryForStationName = restTemplate.exchange(
                "http://ts-station-service:12345/api/v1/stationservice/stations/name/" + stationId,
                HttpMethod.GET,
                requestEntityQueryForStationName,
                Response.class);
        Response station = reQueryForStationName.getBody();
//        QueryStation station = restTemplate.postForObject(
//                "http://ts-station-service:12345/station/queryById"
//                ,query,QueryStation.class);
        return (String) station.getData();
    }

    private boolean payDifferentMoney(String orderId, String tripId, String userId, String money, HttpHeaders httpHeaders) {
        PaymentDifferenceInfo info = new PaymentDifferenceInfo();
        info.setOrderId(orderId);
        info.setTripId(tripId);
        info.setUserId(userId);
        info.setPrice(money);

        HttpEntity requestEntityPayDifferentMoney = new HttpEntity(info, httpHeaders);
        ResponseEntity<Response> rePayDifferentMoney = restTemplate.exchange(
                "http://ts-inside-payment-service:18673/api/v1/inside_pay_service/inside_payment/difference",
                HttpMethod.POST,
                requestEntityPayDifferentMoney,
                Response.class);
        Response result = rePayDifferentMoney.getBody();
//        boolean result = restTemplate.postForObject(
//                "http://ts-inside-payment-service:18673/inside_payment/payDifference"
//                ,info,Boolean.class);
        if (result.getStatus() == 1)
            return true;
        return false;
    }

    private boolean drawBackMoney(String userId, String money, HttpHeaders httpHeaders) {

        HttpEntity requestEntityDrawBackMoney = new HttpEntity(httpHeaders);
        ResponseEntity<Response> reDrawBackMoney = restTemplate.exchange(
                "http://ts-inside-payment-service:18673/api/v1/inside_pay_service/inside_payment/drawback/" + userId + "/" + money,
                HttpMethod.GET,
                requestEntityDrawBackMoney,
                Response.class);
        Response result = reDrawBackMoney.getBody();
//        boolean result = restTemplate.postForObject(
//                "http://ts-inside-payment-service:18673/inside_payment/drawBack"
//                ,info,Boolean.class);
        if (result.getStatus() == 1)
            return true;
        return false;
    }

}
