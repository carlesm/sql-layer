insert into customers (cid, name) select cid+100, name from customers EXCEPT select iid, oid from items