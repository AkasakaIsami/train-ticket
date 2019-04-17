package travel.service;

import edu.fudan.common.util.JsonUtils;
import edu.fudan.common.util.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import travel.entity.*;
import travel.repository.TripRepository;

import java.util.*;

@Service
public class TravelServiceImpl implements TravelService {

    @Autowired
    private TripRepository repository;

    @Autowired
    private RestTemplate restTemplate;


    @Override
    public Response create(TravelInfo info, HttpHeaders headers) {
        TripId ti = new TripId(info.getTripId());
        if (repository.findByTripId(ti) == null) {
            Trip trip = new Trip(ti, info.getTrainTypeId(), info.getStartingStationId(),
                    info.getStationsId(), info.getTerminalStationId(), info.getStartingTime(), info.getEndTime());
            trip.setRouteId(info.getRouteId());
            repository.save(trip);
            return new Response<>(1, "Create trip:" + ti.toString() + ".", null);
        } else {
            return new Response<>(1, "Trip " + info.getTripId().toString() + " already exists", null);
        }
    }

    @Override
    public Response getRouteByTripId(String tripId, HttpHeaders headers) {
        Route route = null;
        if (null != tripId && tripId.length() >= 2) {
            TripId tripId1 = new TripId(tripId);
            Trip trip = repository.findByTripId(tripId1);
            if (trip != null) {
                route = getRouteByRouteId(trip.getRouteId(), headers);
            }
        }
        if (route != null) {
            return new Response<>(1, "Success", route);
        } else {
            return new Response<>(0, "No Content", null);
        }
    }

    @Override
    public Response getTrainTypeByTripId(String tripId, HttpHeaders headers) {
        TripId tripId1 = new TripId(tripId);
//        GetTrainTypeResult result = new GetTrainTypeResult();
        TrainType trainType = null;
        Trip trip = repository.findByTripId(tripId1);
        if (trip != null) {
            trainType = getTrainType(trip.getTrainTypeId(), headers);
        }
        if (trainType != null) {
            return new Response<>(1, "Success", trainType);
        } else {
            return new Response<>(0, "No Content", null);
        }
    }

    @Override
    public Response getTripByRoute(ArrayList<String> routeIds, HttpHeaders headers) {
        ArrayList<ArrayList<Trip>> tripList = new ArrayList<>();
        for (String routeId : routeIds) {
            ArrayList<Trip> tempTripList = repository.findByRouteId(routeId);
            if (tempTripList == null) {
                tempTripList = new ArrayList<>();
            }
            tripList.add(tempTripList);
        }
        if (tripList.size() > 0) {
            return new Response<>(1, "Success", tripList);
        } else {
            return new Response<>(0, "No Content", null);
        }
    }


    @Override
    public Response retrieve(String tripId, HttpHeaders headers) {
        TripId ti = new TripId(tripId);
        Trip trip = repository.findByTripId(ti);
        if (trip != null) {
            return new Response<>(1, "Search Trip Success by Trip Id " + tripId, trip);
        } else {
            return new Response<>(0, "No Content according to tripId" + tripId, null);
        }
    }

    @Override
    public Response update(TravelInfo info, HttpHeaders headers) {
        TripId ti = new TripId(info.getTripId());
        if (repository.findByTripId(ti) != null) {
            Trip trip = new Trip(ti, info.getTrainTypeId(), info.getStartingStationId(),
                    info.getStationsId(), info.getTerminalStationId(), info.getStartingTime(), info.getEndTime());
            trip.setRouteId(info.getRouteId());
            repository.save(trip);
            return new Response<>(1, "Update trip:" + ti.toString(), trip);
        } else {
            return new Response<>(1, "Trip" + info.getTripId().toString() + "doesn 't exists", null);
        }
    }

    @Override
    public Response delete(String tripId, HttpHeaders headers) {
        TripId ti = new TripId(tripId);
        if (repository.findByTripId(ti) != null) {
            repository.deleteByTripId(ti);
            return new Response<>(1, "Delete trip:" + tripId + ".", tripId);
        } else {
            return new Response<>(0, "Trip " + tripId + " doesn't exist.", null);
        }
    }

    @Override
    public Response query(TripInfo info, HttpHeaders headers) {

        //获取要查询的车次的起始站和到达站。这里收到的起始站和到达站都是站的名称，所以需要发两个请求转换成站的id
        String startingPlaceName = info.getStartingPlace();
        String endPlaceName = info.getEndPlace();
        String startingPlaceId = queryForStationId(startingPlaceName, headers);
        String endPlaceId = queryForStationId(endPlaceName, headers);

        //这个是最终的结果
        List<TripResponse> list = new ArrayList<>();

        //查询所有的车次信息
        List<Trip> allTripList = repository.findAll();
        for (Trip tempTrip : allTripList) {
            //拿到这个车次的具体路线表
            Route tempRoute = getRouteByRouteId(tempTrip.getRouteId(), headers);
            //检查这个车次的路线表。检查要求的起始站和到达站在不在车次路线的停靠站列表中
            //并检查起始站的位置在到达站之前。满足以上条件的车次被加入返回列表
            if (tempRoute.getStations().contains(startingPlaceId) &&
                    tempRoute.getStations().contains(endPlaceId) &&
                    tempRoute.getStations().indexOf(startingPlaceId) < tempRoute.getStations().indexOf(endPlaceId)) {
                TripResponse response = getTickets(tempTrip, tempRoute, startingPlaceId, endPlaceId, startingPlaceName, endPlaceName, info.getDepartureTime(), headers);
                if (response == null) {
                    return new Response<>(0, "No Trip info content", null);
                }
                list.add(response);
            }
        }
        return new Response<>(1, "Success", list);
    }

    @Override
    public Response getTripAllDetailInfo(TripAllDetailInfo gtdi, HttpHeaders headers) {
        TripAllDetail gtdr = new TripAllDetail();
        System.out.println("[TravelService] [TripAllDetailInfo] TripId:" + gtdi.getTripId());
        Trip trip = repository.findByTripId(new TripId(gtdi.getTripId()));
        if (trip == null) {
            gtdr.setTripResponse(null);
            gtdr.setTrip(null);
        } else {
            String startingPlaceName = gtdi.getFrom();
            String endPlaceName = gtdi.getTo();
            String startingPlaceId = queryForStationId(startingPlaceName, headers);
            String endPlaceId = queryForStationId(endPlaceName, headers);
            Route tempRoute = getRouteByRouteId(trip.getRouteId(), headers);

            TripResponse tripResponse = getTickets(trip, tempRoute, startingPlaceId, endPlaceId, gtdi.getFrom(), gtdi.getTo(), gtdi.getTravelDate(), headers);
            if (tripResponse == null) {
                gtdr.setTripResponse(null);
                gtdr.setTrip(null);
            } else {
                gtdr.setTripResponse(tripResponse);
                gtdr.setTrip(repository.findByTripId(new TripId(gtdi.getTripId())));
            }
        }
        return new Response<>(1, "Success", gtdr);
    }

    private TripResponse getTickets(Trip trip, Route route, String startingPlaceId, String endPlaceId, String startingPlaceName, String endPlaceName, Date departureTime, HttpHeaders headers) {

        //判断所查日期是否在当天及之后
        if (!afterToday(departureTime)) {
            return null;
        }

        Travel query = new Travel();
        query.setTrip(trip);
        query.setStartingPlace(startingPlaceName);
        query.setEndPlace(endPlaceName);
        query.setDepartureTime(departureTime);

        HttpEntity requestEntity = new HttpEntity(query, headers);
        ResponseEntity<Response> re = restTemplate.exchange(
                "http://ts-ticketinfo-service:15681/api/v1/ticketinfoservice/ticketinfo",
                HttpMethod.POST,
                requestEntity,
                Response.class);
        TravelResult resultForTravel = JsonUtils.conveterObject(re.getBody().getData(), TravelResult.class);

//        if(resultForTravel.isStatus() == false && resultForTravel.getMessage().contains("Basic-Service Unavailable")){
//            System.out.println("Basic-Service Unavailable");
//            return null;
//        }

//        TravelResult resultForTravel = restTemplate.postForObject(
//                "http://ts-ticketinfo-service:15681/ticketinfo/queryForTravel", query ,TravelResult.class);

        //车票订单_高铁动车（已购票数）
        requestEntity = new HttpEntity(headers);
        ResponseEntity<Response> re2 = restTemplate.exchange(
                "http://ts-order-service:12031/api/v1/orderservice/order/" + departureTime + "/" + trip.getTripId().toString(),
                HttpMethod.GET,
                requestEntity,
                Response.class);
        Response result = re2.getBody();

//        ResultSoldTicket result = restTemplate.postForObject(
//                "http://ts-order-service:12031/order/calculate", information ,ResultSoldTicket.class);
        if (result == null) {
            System.out.println("soldticket TripInfo doesn't exist");
            return null;
        }
        //设置返回的车票信息
        TripResponse response = new TripResponse();
        if (queryForStationId(startingPlaceName, headers).equals(trip.getStartingStationId()) &&
                queryForStationId(endPlaceName, headers).equals(trip.getTerminalStationId())) {
            response.setConfortClass(50);
            response.setEconomyClass(50);
        } else {
            response.setConfortClass(50);
            response.setEconomyClass(50);
        }

        int first = getRestTicketNumber(departureTime, trip.getTripId().toString(),
                startingPlaceName, endPlaceName, SeatClass.FIRSTCLASS.getCode(), headers);

        int second = getRestTicketNumber(departureTime, trip.getTripId().toString(),
                startingPlaceName, endPlaceName, SeatClass.SECONDCLASS.getCode(), headers);
        response.setConfortClass(first);
        response.setEconomyClass(second);

        response.setStartingStation(startingPlaceName);
        response.setTerminalStation(endPlaceName);

        //计算车从起始站开出的距离
        int indexStart = route.getStations().indexOf(startingPlaceId);
        int indexEnd = route.getStations().indexOf(endPlaceId);
        int distanceStart = route.getDistances().get(indexStart) - route.getDistances().get(0);
        int distanceEnd = route.getDistances().get(indexEnd) - route.getDistances().get(0);
        TrainType trainType = getTrainType(trip.getTrainTypeId(), headers);
        //根据列车平均运行速度计算列车运行时间
        int minutesStart = 60 * distanceStart / trainType.getAverageSpeed();
        int minutesEnd = 60 * distanceEnd / trainType.getAverageSpeed();

        Calendar calendarStart = Calendar.getInstance();
        calendarStart.setTime(trip.getStartingTime());
        calendarStart.add(Calendar.MINUTE, minutesStart);
        response.setStartingTime(calendarStart.getTime());
        System.out.println("[Train Service]计算时间：" + minutesStart + " 时间:" + calendarStart.getTime().toString());

        Calendar calendarEnd = Calendar.getInstance();
        calendarEnd.setTime(trip.getStartingTime());
        calendarEnd.add(Calendar.MINUTE, minutesEnd);
        response.setEndTime(calendarEnd.getTime());
        System.out.println("[Train Service]计算时间：" + minutesEnd + " 时间:" + calendarEnd.getTime().toString());

        response.setTripId(new TripId(((SoldTicket) result.getData()).getTrainNumber()));
        response.setTrainTypeId(trip.getTrainTypeId());
        response.setPriceForConfortClass(resultForTravel.getPrices().get("confortClass"));
        response.setPriceForEconomyClass(resultForTravel.getPrices().get("economyClass"));

        return response;
    }

    @Override
    public Response queryAll(HttpHeaders headers) {
        List<Trip> tripList = repository.findAll();
        if (tripList != null && tripList.size() > 0)
            return new Response<>(1, "Success", tripList);
        return new Response<>(0, "No Content", null);
    }

    private static boolean afterToday(Date date) {
        Calendar calDateA = Calendar.getInstance();
        Date today = new Date();
        calDateA.setTime(today);

        Calendar calDateB = Calendar.getInstance();
        calDateB.setTime(date);

        if (calDateA.get(Calendar.YEAR) > calDateB.get(Calendar.YEAR)) {
            return false;
        } else if (calDateA.get(Calendar.YEAR) == calDateB.get(Calendar.YEAR)) {
            if (calDateA.get(Calendar.MONTH) > calDateB.get(Calendar.MONTH)) {
                return false;
            } else if (calDateA.get(Calendar.MONTH) == calDateB.get(Calendar.MONTH)) {
                if (calDateA.get(Calendar.DAY_OF_MONTH) > calDateB.get(Calendar.DAY_OF_MONTH)) {
                    return false;
                } else {
                    return true;
                }
            } else {
                return true;
            }
        } else {
            return true;
        }
    }

    private TrainType getTrainType(String trainTypeId, HttpHeaders headers) {
        HttpEntity requestEntity = new HttpEntity(headers);
        ResponseEntity<Response<TrainType>> re = restTemplate.exchange(
                "http://ts-train-service:14567/api/v1/trainservice/trains/" + trainTypeId,
                HttpMethod.GET,
                requestEntity,
                new ParameterizedTypeReference<Response<TrainType>>() {
                });

//        TrainType trainType = restTemplate.postForObject(
//                "http://ts-train-service:14567/train/retrieve", info, TrainType.class);
        return re.getBody().getData();
    }

    private String queryForStationId(String stationName, HttpHeaders headers) {
        HttpEntity requestEntity = new HttpEntity(headers);
        ResponseEntity<Response<String>> re = restTemplate.exchange(
                "http://ts-ticketinfo-service:15681/api/v1/ticketinfoservice/ticketinfo/" + stationName,
                HttpMethod.GET,
                requestEntity,
                new ParameterizedTypeReference<Response<String>>() {
                });
//        String id = restTemplate.postForObject(
//                "http://ts-ticketinfo-service:15681/ticketinfo/queryForStationId", query ,String.class);
        return re.getBody().getData();
    }

    private Route getRouteByRouteId(String routeId, HttpHeaders headers) {
        System.out.println("[Travel Service][Get Route By Id] Route ID：" + routeId);
        HttpEntity requestEntity = new HttpEntity(headers);
        ResponseEntity<Response> re = restTemplate.exchange(
                "http://ts-route-service:11178/api/v1/routeservice/routes/" + routeId,
                HttpMethod.GET,
                requestEntity,
                Response.class);
        Response routeRes = re.getBody();
//        GetRouteResult result = restTemplate.getForObject(
//                "http://ts-route-service:11178/route/queryById/" + routeId,
//                GetRouteResult.class);
        Route route1 = new Route();
        System.out.println("Routes Response is : " + routeRes.toString());
        if (routeRes.getStatus() == 1) {
            route1 = JsonUtils.conveterObject(routeRes.getData(), Route.class);
            System.out.println("Route is: " + route1.toString());
        }
        return route1;
    }

    private int getRestTicketNumber(Date travelDate, String trainNumber, String startStationName, String endStationName, int seatType, HttpHeaders headers) {
        Seat seatRequest = new Seat();

        String fromId = queryForStationId(startStationName, headers);
        String toId = queryForStationId(endStationName, headers);

        seatRequest.setDestStation(toId);
        seatRequest.setStartStation(fromId);
        seatRequest.setTrainNumber(trainNumber);
        seatRequest.setTravelDate(travelDate);
        seatRequest.setSeatType(seatType);

        HttpEntity requestEntity = new HttpEntity(seatRequest, headers);
        ResponseEntity<Response<Integer>> re = restTemplate.exchange(
                "http://ts-seat-service:18898/api/v1/seatservice/seats/left_tickets",
                HttpMethod.POST,
                requestEntity,
                new ParameterizedTypeReference<Response<Integer>>() {
                });
        int restNumber = re.getBody().getData();
//        int restNumber = restTemplate.postForObject(
//                "http://ts-seat-service:18898/seat/getLeftTicketOfInterval",
//                seatRequest,Integer.class
//        );

        return restNumber;
    }

    @Override
    public Response adminQueryAll(HttpHeaders headers) {
        List<Trip> trips = repository.findAll();
        ArrayList<AdminTrip> adminTrips = new ArrayList<AdminTrip>();
        for (Trip trip : trips) {
            AdminTrip adminTrip = new AdminTrip();
            adminTrip.setTrip(trip);
            adminTrip.setRoute(getRouteByRouteId(trip.getRouteId(), headers));
            adminTrip.setTrainType(getTrainType(trip.getTrainTypeId(), headers));
            adminTrips.add(adminTrip);
        }
        if (adminTrips.size() > 0) {
            return new Response<>(1, "Success", adminTrips);
        } else {
            return new Response<>(0, "No Content", null);
        }
    }
}
