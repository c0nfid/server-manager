#!/bin/sh
# srvmgr-docker — агент Server Manager (Android) для работы с Docker.
# Устанавливается в /usr/local/bin/srvmgr-docker. Данные выводятся построчно в JSON.
VERSION=1.0.0
export LC_ALL=C

die() { echo "$*" >&2; exit 1; }
need_docker() { command -v docker >/dev/null 2>&1 || die "Docker не установлен"; }
# Имя или ID контейнера/тома: буквы, цифры, . _ -
valid_name() { case "$1" in ''|-*|*[!A-Za-z0-9._-]*) return 1 ;; esac; return 0; }
# Ссылка на образ: registry/repo:tag@sha256:...
valid_ref() { case "$1" in ''|-*|*[!A-Za-z0-9._/:@-]*) return 1 ;; esac; return 0; }

compose_v2() { docker compose version >/dev/null 2>&1; }

cmd=$1
[ $# -gt 0 ] && shift

case "$cmd" in
  version)
    echo "srvmgr-docker $VERSION"
    ;;
  info)
    echo '@@AGENT'; echo "$VERSION"
    echo '@@DOCKER'; if command -v docker >/dev/null 2>&1; then echo yes; else echo no; fi
    echo '@@CLIENT'; docker version --format '{{.Client.Version}}' 2>/dev/null
    echo '@@SERVER'; docker version --format '{{.Server.Version}}' 2>/dev/null
    echo '@@ACCESS'; if docker info >/dev/null 2>&1; then echo ok; else echo denied; fi
    echo '@@COMPOSE'
    if compose_v2; then
      echo "v2 $(docker compose version --short 2>/dev/null)"
    elif command -v docker-compose >/dev/null 2>&1; then
      echo "v1 $(docker-compose version --short 2>/dev/null)"
    else
      echo none
    fi
    echo '@@USER'; id -un
    echo '@@GROUP'; if id -nG 2>/dev/null | tr ' ' '\n' | grep -qx docker; then echo yes; else echo no; fi
    echo '@@END'
    ;;
  ps)
    need_docker
    docker ps -a --no-trunc --format '{{json .}}'
    ;;
  stats)
    need_docker
    docker stats --no-stream --format '{{json .}}'
    ;;
  images)
    need_docker
    docker images --format '{{json .}}'
    ;;
  volumes)
    need_docker
    docker volume ls --format '{{json .}}'
    ;;
  networks)
    need_docker
    docker network ls --format '{{json .}}'
    ;;
  compose-ls)
    need_docker
    compose_v2 || die "Нужен Docker Compose v2 (команда docker compose)"
    docker compose ls -a --format json
    ;;
  logs)
    valid_name "$1" || die "Неверное имя контейнера"
    n=${2:-200}
    case "$n" in ''|*[!0-9]*) n=200 ;; esac
    docker logs --tail "$n" --timestamps "$1" 2>&1
    ;;
  inspect)
    valid_name "$1" || die "Неверное имя контейнера"
    docker inspect "$1"
    ;;
  container)
    action=$1
    id=$2
    valid_name "$id" || die "Неверное имя контейнера"
    case "$action" in
      start|stop|restart|kill|pause|unpause) docker "$action" "$id" ;;
      rm) docker rm "$id" ;;
      rm-force) docker rm -f "$id" ;;
      *) die "Неизвестное действие: $action" ;;
    esac
    ;;
  image-pull)
    valid_ref "$1" || die "Неверное имя образа"
    docker pull "$1"
    ;;
  image-rm)
    valid_ref "$1" || die "Неверное имя образа"
    docker rmi "$1"
    ;;
  prune)
    case "$1" in
      images) docker image prune -f ;;
      images-all) docker image prune -a -f ;;
      containers) docker container prune -f ;;
      volumes) docker volume prune -f ;;
      networks) docker network prune -f ;;
      *) die "Неизвестный тип очистки: $1" ;;
    esac
    ;;
  compose)
    action=$1
    project=$2
    files=$3
    compose_v2 || die "Нужен Docker Compose v2 (команда docker compose)"
    case "$project" in ''|-*|*[!a-z0-9_-]*) die "Неверное имя проекта" ;; esac
    set -- -p "$project"
    if [ -n "$files" ]; then
      set -f
      old_ifs=$IFS
      IFS=,
      for f in $files; do set -- "$@" -f "$f"; done
      IFS=$old_ifs
      set +f
    fi
    case "$action" in
      up) docker compose "$@" up -d ;;
      down) docker compose "$@" down ;;
      pull) docker compose "$@" pull ;;
      restart) docker compose "$@" restart ;;
      stop) docker compose "$@" stop ;;
      start) docker compose "$@" start ;;
      logs) docker compose "$@" logs --tail 200 --timestamps --no-color 2>&1 ;;
      *) die "Неизвестное действие compose: $action" ;;
    esac
    ;;
  *)
    die "Неизвестная команда: $cmd (version, info, ps, stats, images, volumes, networks, compose-ls, logs, inspect, container, image-pull, image-rm, prune, compose)"
    ;;
esac
