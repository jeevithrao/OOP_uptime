package storage;

import model.ServiceStatus;

/**
 * What is saved about a service: plain values, not a live Service object. A Service carries a
 * Random and demo switches that are not worth saving; the engine rebuilds a real Service from
 * this record through ServiceFactory. (Records are immutable value carriers.)
 */
public record ServiceRecord(String type, String id, String name, double failureRate, ServiceStatus status) { }
