package cancel.service;

import cancel.entity.*;
import edu.fudan.common.util.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.text.DecimalFormat;
import java.util.Calendar;
import java.util.Date;

@Service
public class CancelServiceImpl implements CancelService {

    @Autowired
    private RestTemplate restTemplate;

    @Override
    public Response cancelOrder(String orderId, String loginId, HttpHeaders headers) throws Exception {
        GetOrderByIdInfo getFromOrderInfo = new GetOrderByIdInfo();
        getFromOrderInfo.setOrderId(orderId);
        Response<Order> orderResult = getOrderByIdFromOrder(orderId, headers);
        if (orderResult.getStatus() == 1) {
            System.out.println("[Cancel Order Service][Cancel Order] Order found G|H");
            Order order =  orderResult.getData();
            if (order.getStatus() == OrderStatus.NOTPAID.getCode()
                    || order.getStatus() == OrderStatus.PAID.getCode() || order.getStatus() == OrderStatus.CHANGE.getCode()) {

                order.setStatus(OrderStatus.CANCEL.getCode());

                Response changeOrderResult = cancelFromOrder(order, headers);
                // 0 -- not find order   1 - cancel success
                if (changeOrderResult.getStatus() == 1) {

                    System.out.println("[Cancel Order Service][Cancel Order] Success.");
                    //Draw back money
                    String money = calculateRefund(order);
                    boolean status = drawbackMoney(money, loginId, headers);
                    if (status) {
                        System.out.println("[Cancel Order Service][Draw Back Money] Success.");

                        GetAccountByIdInfo getAccountByIdInfo = new GetAccountByIdInfo();
                        getAccountByIdInfo.setAccountId(order.getAccountId().toString());
                        // todo
                        GetAccountByIdResult result = getAccount(getAccountByIdInfo, headers);
                        if (result.isStatus() == false) {
                            return null;
                        }
                        NotifyInfo notifyInfo = new NotifyInfo();
                        notifyInfo.setDate(new Date().toString());
                        notifyInfo.setEmail(result.getAccount().getEmail());
                        notifyInfo.setStartingPlace(order.getFrom());
                        notifyInfo.setEndPlace(order.getTo());
                        notifyInfo.setUsername(result.getAccount().getName());
                        notifyInfo.setSeatNumber(order.getSeatNumber());
                        notifyInfo.setOrderNumber(order.getId().toString());
                        notifyInfo.setPrice(order.getPrice());
                        notifyInfo.setSeatClass(SeatClass.getNameByCode(order.getSeatClass()));
                        notifyInfo.setStartingTime(order.getTravelTime().toString());

                        sendEmail(notifyInfo, headers);

                    } else {
                        System.out.println("[Cancel Order Service][Draw Back Money] Fail.");
                    }
                    return new Response<>(1, "Success.", null);
                } else {
                    System.out.println("[Cancel Order Service][Cancel Order] Fail.Reason:" + changeOrderResult.getMsg());
                    return new Response<>(0, changeOrderResult.getMsg(), null);
                }

            } else {
                System.out.println("[Cancel Order Service][Cancel Order] Order Status Not Permitted.");
                return new Response<>(0, "Order Status Cancel Not Permitted", null);
            }
        } else {

            Response<Order> orderOtherResult = getOrderByIdFromOrderOther(orderId, headers);
            if (orderOtherResult.getStatus() == 1) {
                System.out.println("[Cancel Order Service][Cancel Order] Order found Z|K|Other");

                Order order =   orderOtherResult.getData();
                if (order.getStatus() == OrderStatus.NOTPAID.getCode()
                        || order.getStatus() == OrderStatus.PAID.getCode() || order.getStatus() == OrderStatus.CHANGE.getCode()) {

                    System.out.println("[Cancel Order Service][Cancel Order] Order status ok");

                    order.setStatus(OrderStatus.CANCEL.getCode());
                    Response changeOrderResult = cancelFromOtherOrder(order, headers);

                    if (changeOrderResult.getStatus() == 1) {
                        System.out.println("[Cancel Order Service][Cancel Order] Success.");
                        //Draw back money
                        String money = calculateRefund(order);
                        boolean status = drawbackMoney(money, loginId, headers);
                        if (status) {
                            System.out.println("[Cancel Order Service][Draw Back Money] Success.");
                        } else {
                            System.out.println("[Cancel Order Service][Draw Back Money] Fail.");
                        }
                        return new Response<>(1, "Success.", null);
                    } else {
                        System.out.println("[Cancel Order Service][Cancel Order] Fail.Reason:" + changeOrderResult.getMsg());
                        return new Response<>(0, "Fail.Reason:" + changeOrderResult.getMsg(), null);
                    }
                } else {
                    System.out.println("[Cancel Order Service][Cancel Order] Order Status Not Permitted.");
                    return new Response<>(0, "Order Status Cancel Not Permitted", null);
                }
            } else {
                System.out.println("[Cancel Order Service][Cancel Order] Order Not Found.");
                return new Response<>(0, "Order Not Found.", null);
            }
        }
    }

    public boolean sendEmail(NotifyInfo notifyInfo, HttpHeaders headers) {
        System.out.println("[Cancel Order Service][Send Email]");
        HttpEntity requestEntity = new HttpEntity(notifyInfo, headers);
        ResponseEntity<Boolean> re = restTemplate.exchange(
                "http://ts-notification-service:17853/api/v1/notifyservice/notification/order_cancel_success",
                HttpMethod.POST,
                requestEntity,
                Boolean.class);
        boolean result = re.getBody();
//        boolean result = restTemplate.postForObject(
//                "http://ts-notification-service:17853/notification/order_cancel_success",
//                notifyInfo,
//                Boolean.class
//        );
        return result;
    }

    @Override
    public Response calculateRefund(String orderId, HttpHeaders headers) {

        Response<Order> orderResult = getOrderByIdFromOrder(orderId, headers);
        if (orderResult.getStatus() == 1) {
            Order order =   orderResult.getData();
            if (order.getStatus() == OrderStatus.NOTPAID.getCode()
                    || order.getStatus() == OrderStatus.PAID.getCode()) {
                if (order.getStatus() == OrderStatus.NOTPAID.getCode()) {
                    System.out.println("[Cancel Order][Refund Price] From Order Service.Not Paid.");
                    return new Response<>(1, "Success. Refoud 0", 0);
                } else {
                    System.out.println("[Cancel Order][Refund Price] From Order Service.Paid.");
                    return new Response<>(1, "Success. ", calculateRefund(order));
                }
            } else {
                System.out.println("[Cancel Order][Refund Price] Order. Cancel Not Permitted.");
                return new Response<>(0, "Order Status Cancel Not Permitted, Refound error", null);
            }
        } else {
            GetOrderByIdInfo getFromOtherOrderInfo = new GetOrderByIdInfo();
            getFromOtherOrderInfo.setOrderId(orderId);
            Response<Order> orderOtherResult = getOrderByIdFromOrderOther(orderId, headers);
            if (orderOtherResult.getStatus() == 1) {
                Order order =   orderOtherResult.getData();
                if (order.getStatus() == OrderStatus.NOTPAID.getCode()
                        || order.getStatus() == OrderStatus.PAID.getCode()) {
                    if (order.getStatus() == OrderStatus.NOTPAID.getCode()) {
                        System.out.println("[Cancel Order][Refund Price] From Order Other Service.Not Paid.");
                        return new Response<>(1, "Success, Refound 0", 0);
                    } else {
                        System.out.println("[Cancel Order][Refund Price] From Order Other Service.Paid.");
                        return new Response<>(1, "Success", calculateRefund(order));
                    }
                } else {
                    System.out.println("[Cancel Order][Refund Price] Order Other. Cancel Not Permitted.");
                    return new Response<>(0, "Order Status Cancel Not Permitted", null);
                }
            } else {
                System.out.println("[Cancel Order][Refund Price] Order not found.");
                return new Response<>(0, "Order Not Found", null);
            }
        }
    }

    private String calculateRefund(Order order) {
        if (order.getStatus() == OrderStatus.NOTPAID.getCode()) {
            return "0.00";
        }
        System.out.println("[Cancel Order] Order Travel Date:" + order.getTravelDate().toString());
        Date nowDate = new Date();
        Calendar cal = Calendar.getInstance();
        cal.setTime(order.getTravelDate());
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH);
        int day = cal.get(Calendar.DAY_OF_MONTH);
        Calendar cal2 = Calendar.getInstance();
        cal2.setTime(order.getTravelTime());
        int hour = cal2.get(Calendar.HOUR);
        int minute = cal2.get(Calendar.MINUTE);
        int second = cal2.get(Calendar.SECOND);
        Date startTime = new Date(year,
                month,
                day,
                hour,
                minute,
                second);
        System.out.println("[Cancel Order] nowDate  :" + nowDate.toString());
        System.out.println("[Cancel Order] startTime:" + startTime.toString());
        if (nowDate.after(startTime)) {
            System.out.println("[Cancel Order] Ticket expire refund 0");
            return "0";
        } else {
            double totalPrice = Double.parseDouble(order.getPrice());
            double price = totalPrice * 0.8;
            DecimalFormat priceFormat = new java.text.DecimalFormat("0.00");
            String str = priceFormat.format(price);
            System.out.println("[Cancel Order]calculate refund - " + str);
            return str;
        }
    }


    private Response cancelFromOrder(Order order, HttpHeaders headers) {
        System.out.println("[Cancel Order Service][Change Order Status] Changing....");

//        ChangeOrderResult result = restTemplate.postForObject("http://ts-order-service:12031/order/update",info,ChangeOrderResult.class);
        HttpEntity requestEntity = new HttpEntity(order, headers);
        ResponseEntity<Response> re = restTemplate.exchange(
                "http://ts-order-service:12031/api/v1/orderservice/order",
                HttpMethod.PUT,
                requestEntity,
                Response.class);
        Response result = re.getBody();

        return result;
    }

    private Response cancelFromOtherOrder(Order info, HttpHeaders headers) {
        System.out.println("[Cancel Order Service][Change Order Status] Changing....");
        HttpEntity requestEntity = new HttpEntity(info, headers);
        ResponseEntity<Response> re = restTemplate.exchange(
                "http://ts-order-other-service:12032/api/v1/orderOtherService/orderOther",
                HttpMethod.PUT,
                requestEntity,
                Response.class);
        Response result = re.getBody();
//        ChangeOrderResult result = restTemplate.postForObject("http://ts-order-other-service:12032/orderOther/update",info,ChangeOrderResult.class);
        return result;
    }

    public boolean drawbackMoney(String money, String userId, HttpHeaders headers) {
        System.out.println("[Cancel Order Service][Draw Back Money] Draw back money...");

        HttpEntity requestEntity = new HttpEntity(headers);
        ResponseEntity<Response> re = restTemplate.exchange(
                "http://ts-inside-payment-service:18673/api/v1/inside_pay_service/inside_payment/drawback/" + userId + "/" + money,
                HttpMethod.GET,
                requestEntity,
                Response.class);
        Response result = re.getBody();
//        String result = restTemplate.postForObject("http://ts-inside-payment-service:18673/inside_payment/drawBack",info,String.class);
        if (result.getStatus() == 1) {
            return true;
        } else {
            return false;
        }
    }

    public GetAccountByIdResult getAccount(GetAccountByIdInfo info, HttpHeaders headers) {
        System.out.println("[Cancel Order Service][Get By Id]");
        HttpEntity requestEntity = new HttpEntity(info, headers);
        ResponseEntity<GetAccountByIdResult> re = restTemplate.exchange(
                "http://ts-sso-service:12349/account/findById",
                HttpMethod.POST,
                requestEntity,
                GetAccountByIdResult.class);
        GetAccountByIdResult result = re.getBody();
//        GetAccountByIdResult result = restTemplate.postForObject(
//                "http://ts-sso-service:12349/account/findById",
//                info,
//                GetAccountByIdResult.class
//        );
        return result;
    }

    private Response<Order> getOrderByIdFromOrder(String orderId, HttpHeaders headers) {
        System.out.println("[Cancel Order Service][Get Order] Getting....");
        HttpEntity requestEntity = new HttpEntity(headers);
        ResponseEntity<Response<Order>> re = restTemplate.exchange(
                "http://ts-order-service:12031/api/v1/orderservice/order/" + orderId,
                HttpMethod.GET,
                requestEntity,
                new ParameterizedTypeReference<Response<Order>>() {
                });
        Response<Order> cor = re.getBody();
//        GetOrderResult cor = restTemplate.postForObject(
//                "http://ts-order-service:12031/order/getById/"
//                ,info,GetOrderResult.class);
        return cor;
    }

    private Response<Order> getOrderByIdFromOrderOther(String orderId, HttpHeaders headers) {
        System.out.println("[Cancel Order Service][Get Order] Getting....");
        HttpEntity requestEntity = new HttpEntity(  headers);
        ResponseEntity<Response<Order>> re = restTemplate.exchange(
                "http://ts-order-other-service:12032/api/v1/orderOtherService/orderOther/" + orderId,
                HttpMethod.GET,
                requestEntity,
                new ParameterizedTypeReference<Response<Order>>() {
                });
        Response<Order> cor = re.getBody();
//        GetOrderResult cor = restTemplate.postForObject(
//                "http://ts-order-other-service:12032/orderOther/getById/"
//                ,info,GetOrderResult.class);
        return cor;
    }

}
